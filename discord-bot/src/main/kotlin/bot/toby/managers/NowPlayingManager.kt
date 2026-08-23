package bot.toby.managers

import bot.toby.lavaplayer.TrackScheduler
import bot.toby.util.ProgressBar
import bot.toby.util.SourceBadges
import bot.toby.util.formatTime
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import common.logging.DiscordLogger
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class NowPlayingManager(
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val guildLastNowPlayingMessage = ConcurrentHashMap<Long, Message>()
    private val scheduledTasks = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val lock = ReentrantReadWriteLock()

    /**
     * Who currently owns the guild's now-playing slot, and a source of tokens
     * that never repeats.
     *
     * A now-playing message exists on Discord a round-trip before this class
     * hears about it, and everything that tidies it up keys off having heard.
     * A track that dies the moment it starts ends inside that window, so the
     * teardown found nothing, and the message landed a moment later with
     * nobody left to remove it: a frozen embed, sitting in the channel for
     * good. The same window makes two posts collide — a retried intro starts
     * before the first send has landed, sees no message to edit, and posts a
     * second one, stranding the first.
     *
     * So a post claims the slot before it is sent and hands the claim back
     * with the message. A claim that no longer matches means the message is
     * already obsolete and is deleted instead of stored.
     */
    private val nowPlayingClaims = ConcurrentHashMap<Long, Long>()
    private val claimSource = AtomicLong()

    /**
     * Claims the guild's now-playing slot for a message about to be posted.
     * Pass the result back to [setNowPlayingMessage] when it lands.
     */
    fun claimNowPlayingSlot(guildId: Long): Long = lock.write {
        claimSource.incrementAndGet().also { nowPlayingClaims[guildId] = it }
    }

    /**
     * @param claim the token from [claimNowPlayingSlot], when the caller took
     *        one out. Null keeps the older unconditional behaviour for callers
     *        that hold the message already rather than having just sent it.
     */
    fun setNowPlayingMessage(guildId: Long, message: Message, claim: Long? = null) {
        lock.write {
            if (claim != null && nowPlayingClaims[guildId] != claim) {
                // Superseded or torn down while this was in the air. Nothing
                // is going to come back for it, so it goes now.
                logger.info {
                    "Now playing message ${message.idLong} for guild $guildId arrived after it was " +
                        "superseded; deleting it rather than leaving it behind"
                }
                message.delete().queue({}, { error ->
                    logger.warn { "Could not delete superseded now playing message ${message.idLong}: ${error.message}" }
                })
                return@write
            }
            logger.info { "Setting now playing message ${message.idLong} for guild $guildId" }
            guildLastNowPlayingMessage[guildId] = message
        }
    }

    fun getLastNowPlayingMessage(guildId: Long): Message? {
        return lock.read {
            guildLastNowPlayingMessage[guildId]?.also {
                logger.info { "Retrieved now playing message ${it.idLong} for guild $guildId" }
            } ?: run {
                logger.info { "No now playing message found for guild $guildId" }
                null
            }
        }
    }

    fun resetNowPlayingMessage(guildId: Long) {
        lock.write {
            logger.info { "Resetting now playing message and cancelling scheduler for guild $guildId" }
            // Dropped first, so a message still in the air lands unclaimed and
            // deletes itself rather than outliving the track it describes.
            nowPlayingClaims.remove(guildId)
            val message = guildLastNowPlayingMessage.remove(guildId)
            scheduledTasks.remove(guildId)?.cancel(true)

            if (message != null) {
                logger.info { "Attempting to delete now playing message ${message.idLong} for guild $guildId" }
                message.delete().submit().whenComplete { _, error ->
                    if (error != null) {
                        logger.error { "Failed to delete now playing message ${message.idLong} for guild $guildId" }
                    } else {
                        logger.info { "Deleted now playing message ${message.idLong} for guild $guildId" }
                    }
                }
            } else {
                logger.info { "No now playing message to reset" }
            }
        }
    }

    fun scheduleNowPlayingUpdate(
        guildId: Long,
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        delay: Long,
        period: Long,
        clipStart: Long? = null,
        clipEnd: Long? = null,
        trackScheduler: TrackScheduler? = null,
        guild: Guild? = null,
    ) {
        cancelScheduledTask(guildId)
        val scheduledTask = scheduler.scheduleAtFixedRate({
            try {
                updateNowPlayingMessage(guildId, track, audioPlayer, clipStart, clipEnd, trackScheduler, guild)
            } catch (_: Exception) {
                logger.error { "Error occurred while updating now playing message" }
            }
        }, delay, period, TimeUnit.SECONDS)

        scheduledTasks[guildId] = scheduledTask
    }

    private fun updateNowPlayingMessage(
        guildId: Long,
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        clipStart: Long?,
        clipEnd: Long?,
        trackScheduler: TrackScheduler?,
        guild: Guild?,
    ) {
        lock.read {
            val message = guildLastNowPlayingMessage[guildId]
            message?.let {
                val embed = buildNowPlayingMessageData(
                    track,
                    audioPlayer.volume,
                    audioPlayer.isPaused,
                    clipStart,
                    clipEnd,
                    trackScheduler,
                    guild,
                )
                message.editMessageEmbeds(embed).queue()
                logger.info { "Updated now playing message ${message.idLong}" }
            }
        }
    }

    fun buildNowPlayingMessageData(
        track: AudioTrack,
        audioPlayer: AudioPlayer,
        clipStart: Long? = null,
        clipEnd: Long? = null,
        trackScheduler: TrackScheduler? = null,
        guild: Guild? = null,
    ): MessageEmbed = buildNowPlayingMessageData(
        track,
        audioPlayer.volume,
        audioPlayer.isPaused,
        clipStart,
        clipEnd,
        trackScheduler,
        guild,
    )

    internal fun buildNowPlayingMessageData(
        track: AudioTrack,
        volume: Int,
        isPaused: Boolean,
        clipStart: Long? = null,
        clipEnd: Long? = null,
        trackScheduler: TrackScheduler? = null,
        guild: Guild? = null,
    ): MessageEmbed {
        val info = track.info
        val sourceName = runCatching { track.sourceManager?.sourceName }.getOrNull()
        val badge = SourceBadges.forSource(sourceName)

        val builder = EmbedBuilder()
            .setColor(badge.color)
            .setTitle(info.title.take(256), info.uri.asHttpUrl())
            .setAuthor(badge.displayName, badge.homeUrl, badge.iconUrl)
            .setDescription(renderDescription(track, info, clipStart, clipEnd))

        info.artworkUrl.asHttpUrl()?.let { builder.setThumbnail(it) }

        builder.addField("Volume", "🔊 $volume", true)
        builder.addField("Paused", if (isPaused) "⏸️ Yes" else "▶️ No", true)
        builder.addField("Loop", if (trackScheduler?.isLooping == true) "🔁 On" else "Off", true)

        renderUpNext(trackScheduler?.queue?.toList())?.let { builder.addField("Up next", it, false) }
        resolveRequesterName(trackScheduler, track, guild)?.let { builder.setFooter("Requested by $it") }

        return builder.build()
    }

    private fun renderDescription(
        track: AudioTrack,
        info: AudioTrackInfo,
        clipStart: Long?,
        clipEnd: Long?,
    ): String = buildString {
        append("By `").append(info.author).append("`\n\n")
        if (info.isStream) {
            append("🔴 **LIVE**")
        } else {
            val effectiveStart = clipStart ?: 0L
            val effectiveEnd = clipEnd ?: track.duration
            val pos = (track.position - effectiveStart).coerceAtLeast(0L)
            val dur = (effectiveEnd - effectiveStart).coerceAtLeast(0L)
            append("`").append(formatTime(pos)).append(" / ").append(formatTime(dur)).append("`\n")
            append(ProgressBar.render(pos, dur))
        }
    }

    private fun renderUpNext(queueSnapshot: List<AudioTrack>?): String? {
        if (queueSnapshot.isNullOrEmpty()) return null
        val preview = queueSnapshot.take(UP_NEXT_PREVIEW).mapIndexed { i, t ->
            val title = t.info.title.let { if (it.length > UP_NEXT_TITLE_MAX) it.take(UP_NEXT_TITLE_MAX - 1) + "…" else it }
            "${i + 1}. `$title`"
        }.joinToString("\n")
        val remaining = queueSnapshot.size - UP_NEXT_PREVIEW
        return if (remaining > 0) "$preview\n_+ $remaining more_" else preview
    }

    private fun resolveRequesterName(scheduler: TrackScheduler?, track: AudioTrack, guild: Guild?): String? {
        val rid = scheduler?.getRequesterId(track) ?: return null
        return guild?.getMemberById(rid)?.effectiveName
    }

    /**
     * JDA validates URLs passed to setTitle / setThumbnail; bounce anything
     * that isn't http(s) (local files, search identifiers, test placeholders)
     * to null so the embed builder doesn't throw.
     */
    private fun String?.asHttpUrl(): String? = this
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    fun cancelScheduledTask(guildId: Long) {
        lock.write {
            scheduledTasks.remove(guildId)?.cancel(true)
        }
    }

    fun clear() {
        lock.write {
            nowPlayingClaims.clear()
            guildLastNowPlayingMessage.clear()
            scheduledTasks.values.forEach { it.cancel(true) }
            scheduledTasks.clear()
        }
    }

    private companion object {
        const val UP_NEXT_PREVIEW = 3
        const val UP_NEXT_TITLE_MAX = 50
    }
}
