package bot.toby.notify

import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
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

/**
 * Single delivery gate for all user-targeted notifications.
 *
 * - [sendDm] gates DM dispatch on the per-user `user_notification_pref`
 *   row; the underlying action (tip, duel, level-up, achievement) is
 *   unaffected when the DM is dropped.
 * - [sendChannel] resolves the appropriate text channel for a
 *   [ChannelRouteKey] (config-key chain + system-channel fallback) and
 *   posts there. Channel routing does NOT consult the user pref store —
 *   admins disable a kind by clearing the configured channel id.
 *
 * Both methods are best-effort. Dispatch failures (closed DMs, missing
 * channel permissions, JDA errors) are logged but never propagate, so a
 * synchronous caller (e.g. an event listener) never stalls or throws on
 * Discord REST hiccups.
 */
@Component
class NotificationRouter(
    @Lazy private val jda: JDA,
    private val prefService: UserNotificationPrefService,
    private val configService: ConfigService,
) {
    private val logger = DiscordLogger.createLogger(this::class.java)

    /**
     * Send a DM to [discordId] only if they're opted in to [kind] for
     * [guildId]. [message] is computed lazily so we don't build embed
     * data for users who won't see it.
     */
    fun sendDm(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        message: () -> MessageCreateData
    ) {
        if (!prefService.isOptedIn(discordId, guildId, kind)) return

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
     * Resolution order (first writable wins):
     *   1. `route.primaryConfigKey` — channel id from `ConfigService`.
     *   2. Each `route.fallbackConfigKeys` in order.
     *   3. [originChannelId] if supplied (e.g. the channel that earned
     *      a level-up).
     *   4. `guild.systemChannel` if [ChannelRouteKey.systemChannelFallback]
     *      is true.
     *
     * Returns silently when no channel resolves — the caller's logic
     * doesn't need to know. Pass [onSent] to react to the sent message
     * (e.g. capture the message id for later edits — used by
     * `LotteryAnnouncer.recordAnnouncement` and
     * `WebDuelOfferNotifier.scheduleTimeoutCleanup`). [message] is built
     * lazily so we don't pay the embed-construction cost when no channel
     * is resolvable.
     */
    fun sendChannel(
        guildId: Long,
        route: ChannelRouteKey,
        originChannelId: Long? = null,
        message: () -> MessageCreateData,
        onSent: ((Message) -> Unit)? = null,
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
            channel.sendMessage(payload).queue(
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
