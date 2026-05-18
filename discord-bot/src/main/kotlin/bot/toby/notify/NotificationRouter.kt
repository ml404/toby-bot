package bot.toby.notify

import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import database.service.UserNotificationPrefService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Single DM-delivery gate for all user-targeted notifications. Every
 * notifier-style component routes through [sendDm] so the per-user
 * preference check (`user_notification_pref`) lives in one place. If
 * the user has opted out (or the default for the kind is opt-out and
 * they haven't set a row), the DM is silently dropped — the underlying
 * action (tip, duel, level-up, achievement) is unaffected.
 *
 * Calls are best-effort. DM dispatch can fail (closed DMs, blocked bot,
 * user left the guild); failures are logged but never propagate, so a
 * synchronous caller (e.g. a slash-command path that triggers an
 * achievement unlock) doesn't stall on Discord REST.
 */
@Component
class NotificationRouter(
    @Lazy private val jda: JDA,
    private val prefService: UserNotificationPrefService
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
}
