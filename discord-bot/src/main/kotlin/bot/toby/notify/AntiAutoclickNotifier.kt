package bot.toby.notify

import bot.toby.command.commands.moderation.AntiAutoclickEmbeds
import common.events.AntiAutoclickEvent
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.service.ConfigService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Posts and maintains a single Discord embed per anti-autoclicker suspicion
 * session. Listens for events from the database module:
 *
 * - [AntiAutoclickEvent.SessionOpened] → send a fresh embed in the configured
 *   mod-log channel (or the guild's system channel as fallback).
 * - [AntiAutoclickEvent.BiasFired]     → bump in-memory counters and schedule
 *   a debounced in-place edit so a saturated burst can't spam the channel
 *   or trip JDA's per-channel edit rate limit.
 * - [AntiAutoclickEvent.SessionClosed] → cancel any pending edit and write
 *   one final summary edit before discarding the session.
 *
 * Session state is in-memory only. A redeploy mid-session orphans the open
 * message (it stays as the last-known state); subsequent events for the
 * same `(user, guild, gameKey)` start a fresh session in a new message.
 */
@Component
class AntiAutoclickNotifier(
    private val jda: JDA,
    private val configService: ConfigService,
    private val clock: Clock = Clock.systemUTC(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "anti-autoclick-edit-debouncer").apply { isDaemon = true }
    },
) {
    private val logger = DiscordLogger.createLogger(this::class.java)

    private val sessions = ConcurrentHashMap<Triple<Long, Long, String>, ActiveSession>()

    private class ActiveSession(
        val channelId: Long,
        val startedAt: Instant,
    ) {
        val fireCount = AtomicInteger(0)
        val currentStreak = AtomicInteger(0)
        val peakStreak = AtomicInteger(0)
        val edgePct = AtomicReference(0.0)
        val messageId = AtomicReference<Long?>(null)
        val pendingEdit = AtomicReference<ScheduledFuture<*>?>(null)
    }

    @EventListener
    fun onOpened(event: AntiAutoclickEvent.SessionOpened) {
        val key = Triple(event.discordId, event.guildId, event.gameKey)
        val guild = jda.getGuildById(event.guildId) ?: run {
            logger.warn("AntiAutoclickEvent.SessionOpened for guild ${event.guildId} but bot is not in that guild; skipping.")
            return
        }
        val channel = resolveChannel(guild) ?: run {
            logger.warn("No usable mod-log/system channel in guild ${event.guildId}; skipping anti-autoclick session embed.")
            return
        }
        val now = Instant.now(clock)
        val session = ActiveSession(channelId = channel.idLong, startedAt = now).also {
            it.currentStreak.set(event.streak)
            it.peakStreak.set(event.streak)
        }
        // Replace any stale orphan in the map (e.g. if a Close was missed at
        // restart and the user re-opened) so counters don't bleed across
        // sessions.
        sessions[key] = session
        runCatching {
            channel.sendMessageEmbeds(
                AntiAutoclickEmbeds.openEmbed(event.discordId, event.gameKey, event.streak, now)
            ).queue(Consumer { msg: Message -> session.messageId.set(msg.idLong) })
        }.onFailure {
            logger.error("Could not post anti-autoclick session embed in guild ${event.guildId}: ${it.message}")
        }
    }

    @EventListener
    fun onFired(event: AntiAutoclickEvent.BiasFired) {
        val key = Triple(event.discordId, event.guildId, event.gameKey)
        val session = sessions[key] ?: run {
            // BiasFired with no active session is normal at startup (the
            // service restarted between the open and the first fire) — no log.
            return
        }
        session.fireCount.incrementAndGet()
        session.currentStreak.set(event.streak)
        session.peakStreak.updateAndGet { current -> if (event.streak > current) event.streak else current }
        session.edgePct.set(event.edgePct)
        scheduleDebouncedEdit(event.discordId, event.gameKey, session)
    }

    @EventListener
    fun onClosed(event: AntiAutoclickEvent.SessionClosed) {
        val key = Triple(event.discordId, event.guildId, event.gameKey)
        val session = sessions.remove(key) ?: return
        session.pendingEdit.getAndSet(null)?.cancel(false)
        val messageId = session.messageId.get() ?: run {
            // The send hadn't yet returned the message ID before the session
            // ended (very short streak). Nothing to edit.
            return
        }
        val channel = jda.getTextChannelById(session.channelId) ?: return
        val endedAt = Instant.now(clock)
        runCatching {
            channel.editMessageEmbedsById(
                messageId,
                AntiAutoclickEmbeds.closedEmbed(
                    discordId = event.discordId,
                    gameKey = event.gameKey,
                    peakStreak = session.peakStreak.get(),
                    totalFires = session.fireCount.get(),
                    startedAt = session.startedAt,
                    endedAt = endedAt,
                ),
            ).queue()
        }.onFailure {
            logger.error("Could not finalise anti-autoclick session embed ${messageId} in guild ${event.guildId}: ${it.message}")
        }
    }

    private fun scheduleDebouncedEdit(
        discordId: Long,
        gameKey: String,
        session: ActiveSession,
    ) {
        // If an edit is already scheduled, this fire is coalesced into it —
        // the runnable always reads the latest counters from the session,
        // so a single edit captures every bias-fire in the window.
        if (session.pendingEdit.get() != null) return
        val task = Runnable {
            session.pendingEdit.set(null)
            performEdit(discordId, gameKey, session)
        }
        val future: ScheduledFuture<*> = scheduler.schedule(task, EDIT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        if (!session.pendingEdit.compareAndSet(null, future)) {
            // Another scheduling lost the race — discard ours.
            future.cancel(false)
        }
    }

    private fun performEdit(discordId: Long, gameKey: String, session: ActiveSession) {
        val messageId = session.messageId.get() ?: return
        val channel = jda.getTextChannelById(session.channelId) ?: return
        runCatching {
            channel.editMessageEmbedsById(
                messageId,
                AntiAutoclickEmbeds.activeEmbed(
                    discordId = discordId,
                    gameKey = gameKey,
                    currentStreak = session.currentStreak.get(),
                    peakStreak = session.peakStreak.get(),
                    fireCount = session.fireCount.get(),
                    edgePct = session.edgePct.get(),
                    startedAt = session.startedAt,
                    now = Instant.now(clock),
                ),
            ).queue()
        }.onFailure {
            logger.error("Could not edit anti-autoclick session embed ${messageId}: ${it.message}")
        }
    }

    private fun resolveChannel(guild: Guild): TextChannel? {
        val configuredId = configService.getConfigByName(
            ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
            guild.idLong.toString(),
        )?.value?.toLongOrNull()
        if (configuredId != null) {
            val configured = guild.getTextChannelById(configuredId)
            if (configured != null && hasWritePermissions(guild, configured)) return configured
            logger.warn("Configured CASINO_MODLOG_CHANNEL_ID=$configuredId for guild ${guild.idLong} is not usable; falling back to system channel.")
        }
        return guild.systemChannel?.takeIf { hasWritePermissions(guild, it) }
    }

    private fun hasWritePermissions(guild: Guild, channel: TextChannel): Boolean =
        guild.selfMember.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)

    /**
     * Drop every in-flight session for [guildId] and cancel any pending
     * debounced edit. Called when the bot leaves the guild — there's no
     * channel to edit against anymore so the message handle and counters
     * are dead weight.
     */
    fun evictGuild(guildId: Long) {
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val (key, session) = iter.next()
            if (key.second != guildId) continue
            iter.remove()
            session.pendingEdit.getAndSet(null)?.cancel(false)
        }
    }

    /**
     * Test-only: force the currently-pending debounced edit to run immediately
     * (and clears the pending future). Returns `true` if there was a pending
     * edit to flush. Production code should never call this; the scheduler
     * handles timing on its own.
     */
    internal fun flushPendingEditForTest(discordId: Long, guildId: Long, gameKey: String): Boolean {
        val session = sessions[Triple(discordId, guildId, gameKey)] ?: return false
        val pending = session.pendingEdit.getAndSet(null) ?: return false
        pending.cancel(false)
        performEdit(discordId, gameKey, session)
        return true
    }

    /**
     * Test-only: snapshot of current session state for the given key, or
     * `null` if no session is active.
     */
    internal fun sessionSnapshotForTest(discordId: Long, guildId: Long, gameKey: String): SessionSnapshot? {
        val s = sessions[Triple(discordId, guildId, gameKey)] ?: return null
        return SessionSnapshot(
            channelId = s.channelId,
            messageId = s.messageId.get(),
            fireCount = s.fireCount.get(),
            currentStreak = s.currentStreak.get(),
            peakStreak = s.peakStreak.get(),
            edgePct = s.edgePct.get(),
            startedAt = s.startedAt,
        )
    }

    internal data class SessionSnapshot(
        val channelId: Long,
        val messageId: Long?,
        val fireCount: Int,
        val currentStreak: Int,
        val peakStreak: Int,
        val edgePct: Double,
        val startedAt: Instant,
    )

    companion object {
        const val EDIT_DEBOUNCE_MS: Long = 2000L
    }
}
