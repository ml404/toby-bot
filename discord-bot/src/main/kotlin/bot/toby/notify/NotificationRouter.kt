package bot.toby.notify

import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import common.notification.Surface
import database.service.ConfigService
import database.service.UserNotificationPrefService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single delivery gate for all user-targeted notifications.
 *
 * Surfaces:
 * - [sendDm] — opens a private channel and sends a Discord DM. Gated
 *   by per-user `(kind, Surface.DM)` opt-in.
 * - [sendChannel] — resolves the appropriate text channel for a
 *   [ChannelRouteKey] (config-key chain + system-channel fallback) and
 *   posts there. Channel posts are admin-gated by config; per-user
 *   `(kind, Surface.CHANNEL)` opt-in suppresses the user's `<@id>`
 *   mention via JDA's `mentionUsers(...)` allow-list when [mentions]
 *   is supplied. The post still happens; opted-out users see the
 *   content but don't get a notification.
 * - [sendPush] — provider-agnostic push notification. No adapter is
 *   wired today; the call drops with a one-shot "no push adapter wired"
 *   WARN once per process so a misconfigured guild doesn't flood logs.
 *   The future provider PR plugs in here.
 *
 * All methods are best-effort. Dispatch failures are logged but never
 * propagate, so a synchronous caller (e.g. an event listener) never
 * stalls or throws on Discord REST hiccups.
 */
@Component
class NotificationRouter(
    @Lazy private val jda: JDA,
    private val prefService: UserNotificationPrefService,
    private val configService: ConfigService,
) {
    private val logger = DiscordLogger.createLogger(this::class.java)
    private val pushAdapterMissingLogged = AtomicBoolean(false)

    /**
     * Send a DM to [discordId] only if they're opted in to [kind] on the
     * DM surface for [guildId]. [message] is computed lazily so we don't
     * build embed data for users who won't see it.
     */
    fun sendDm(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        message: () -> MessageCreateData
    ) {
        if (!prefService.isOptedIn(discordId, guildId, kind, Surface.DM)) return

        val user = runCatching { jda.retrieveUserById(discordId).complete() }
            .getOrElse { err ->
                logger.warn("Could not retrieve user $discordId for $kind DM: ${err.message}")
                return
            }
        if (user == null) {
            logger.warn("retrieveUserById($discordId) returned null; dropping $kind DM.")
            return
        }

        val payload = runCatching { message() }
            .getOrElse { err ->
                logger.warn("Failed to build $kind DM payload for $discordId: ${err.message}")
                return
            }

        runCatching {
            user.openPrivateChannel().queue({ channel ->
                channel.sendMessage(payload).queue(null) { err ->
                    logger.info { "DM ($kind) to $discordId dropped: ${err.message}" }
                }
            }) { err ->
                logger.info { "openPrivateChannel for $discordId failed ($kind): ${err.message}" }
            }
        }.onFailure {
            logger.warn("NotificationRouter dispatch failed for $discordId ($kind): ${it.message}")
        }
    }

    /**
     * Post [message] to the text channel chosen for [route] in [guildId].
     * Resolution chain: `route.primaryConfigKey` → each
     * `route.fallbackConfigKeys` in order → [originChannelId] when
     * supplied → `guild.systemChannel` (if route allows). Returns
     * silently when no channel resolves.
     *
     * When [mentions] is supplied the router filters `mentions.userIds`
     * by per-user `(mentions.kind, Surface.CHANNEL)` opt-in and calls
     * `.mentionUsers(*filtered.toLongArray())` on the action — opted-out
     * users see the content but don't get notified. `EVERYONE` / `HERE`
     * allowed-mention types set by the caller's payload are preserved
     * (JDA's `mentionUsers` only restricts the USER set).
     */
    fun sendChannel(
        guildId: Long,
        route: ChannelRouteKey,
        originChannelId: Long? = null,
        message: () -> MessageCreateData,
        onSent: ((Message) -> Unit)? = null,
        mentions: ChannelMentions? = null,
    ) {
        val guild = jda.getGuildById(guildId) ?: run {
            logger.warn("sendChannel for guild $guildId but bot is not in that guild; skipping ($route).")
            return
        }
        val channel = resolveChannel(guild, route, originChannelId) ?: run {
            logger.info {
                "No writable channel resolved for route $route in guild ${guild.idLong}; skipping."
            }
            return
        }

        val payload = runCatching { message() }
            .getOrElse { err ->
                logger.warn("Failed to build $route payload for guild ${guild.idLong}: ${err.message}")
                return
            }

        runCatching {
            var action = channel.sendMessage(payload)
            if (mentions != null) {
                val whitelist = mentions.userIds
                    .filter { uid -> prefService.isOptedIn(uid, guildId, mentions.kind, Surface.CHANNEL) }
                    .toLongArray()
                action = action.mentionUsers(*whitelist)
            }
            action.queue(
                { sent ->
                    onSent?.let { cb ->
                        runCatching { cb(sent) }.onFailure {
                            logger.warn("$route onSent callback threw: ${it.message}")
                        }
                    }
                },
                { err ->
                    logger.warn("Failed to post $route to #${channel.name}: ${err.message}")
                },
            )
        }.onFailure {
            logger.warn("$route channel dispatch failed: ${it.message}")
        }
    }

    /**
     * Push-notification entry point. No adapter is wired today — the
     * call short-circuits after the opt-in check and logs "no push
     * adapter wired" exactly once per process the first time a
     * deliverable push lands. User opt-ins persist forward-compatibly,
     * so the provider-adapter PR plugs in here without surprise pushes.
     *
     * [message] is built lazily — only when [kind] supports PUSH AND
     * the user is opted in. No-supported-surface returns silently
     * without logging.
     */
    fun sendPush(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        message: () -> PushPayload,
    ) {
        if (!kind.supports(Surface.PUSH)) return
        if (!prefService.isOptedIn(discordId, guildId, kind, Surface.PUSH)) return
        // Build is intentional — if the caller's payload-builder is
        // expensive we still want to short-circuit before it runs (the
        // two guards above). Once we're past them the user wanted this
        // push, so building is justified even when the adapter is
        // missing (the adapter PR will use the result).
        runCatching { message() }
        if (pushAdapterMissingLogged.compareAndSet(false, true)) {
            logger.warn(
                "Push delivery requested for kind $kind (user $discordId guild $guildId) but no " +
                    "PushAdapter is wired. Subsequent push requests will be dropped silently until " +
                    "an adapter ships."
            )
        }
        // TODO(push-adapter PR): forward to PushAdapter.deliver(...) when wired.
    }

    private fun resolveChannel(
        guild: Guild,
        route: ChannelRouteKey,
        originChannelId: Long?,
    ): TextChannel? {
        // 1. primaryConfigKey
        route.primaryConfigKey
            ?.let { key -> channelFromConfig(guild, key) }
            ?.let { return it }
        // 2. fallbackConfigKeys in order
        route.fallbackConfigKeys.forEach { key ->
            channelFromConfig(guild, key)?.let { return it }
        }
        // 3. originChannelId
        originChannelId?.let { id ->
            guild.getTextChannelById(id)?.takeIf { hasSendPerms(guild, it) }?.let { return it }
        }
        // 4. system channel fallback (per-route opt-in)
        if (route.systemChannelFallback) {
            guild.systemChannel?.takeIf { hasSendPerms(guild, it) }?.let { return it }
        }
        return null
    }

    private fun channelFromConfig(guild: Guild, configKey: String): TextChannel? {
        val raw = configService.getConfigByName(configKey, guild.id)?.value?.toLongOrNull()
            ?: return null
        val channel = guild.getTextChannelById(raw) ?: return null
        return channel.takeIf { hasSendPerms(guild, it) }
    }

    private fun hasSendPerms(guild: Guild, channel: TextChannel): Boolean =
        guild.selfMember.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
}

/**
 * Declares which users the caller intends to ping via `<@id>` mentions
 * inside a channel post. The router filters this list by per-user
 * `(kind, Surface.CHANNEL)` opt-in and constrains JDA's user-mention
 * whitelist accordingly — opted-out users see the post text but don't
 * get notified.
 */
data class ChannelMentions(
    val kind: NotificationChannelKind,
    val userIds: List<Long>,
)
