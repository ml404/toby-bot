package web.service

import common.events.AchievementUnlockedEvent
import common.events.LevelUpEvent
import common.events.LotteryDrawnForTicketHolderEvent
import common.events.TipSentEvent
import common.logging.DiscordLogger
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.sse.KeyedSseRegistry

/**
 * Per-user SSE channel that pops engagement events as in-page toasts
 * while the user is browsing. Translates four `ApplicationEvent`s
 * (achievement unlock, level up, tip received, lottery drawn-for-ticket-holder)
 * into named SSE events delivered to every emitter the user has open
 * across their tabs.
 *
 * Storage and lifecycle are delegated to [KeyedSseRegistry] (see its
 * KDoc for the memory profile rationale). This service is purely about
 * event-to-payload translation and routing — the registry handles
 * fan-out, dead-emitter eviction, and bucket lifetime.
 *
 * Why not route through `NotificationRouter`: in-page toasts are not
 * gated by per-(kind, surface) preferences. The user opening the site
 * implicitly opts in; reaching the SSE endpoint already requires an
 * authenticated session. Adding a `Surface.IN_PAGE` enum entry and
 * migrating preference rows would expand the change for no behavioural
 * win — listening to the underlying events directly is the cleanest
 * fit, and a future opt-out toggle can short-circuit client-side.
 *
 * Toast-vs-push overlap is resolved in [sw.js] (foreground-suppress
 * the OS push when a visible client exists). The toast is the
 * "looking-at-it" surface; the push is the "not-looking" surface. The
 * SSE channel fires unconditionally — the SW decides whether the push
 * also pops or sits quiet because the toast already covered it.
 */
@Service
class NotificationSseService(
    private val registry: KeyedSseRegistry<Long> = KeyedSseRegistry(),
) : SseRegistrar {

    override fun register(discordId: Long): SseEmitter =
        registry.register(discordId, mapOf("discordId" to discordId))

    @EventListener
    fun onAchievementUnlocked(event: AchievementUnlockedEvent) {
        // TODO(diag-removal): drop alongside the other [diag] lines once the double-toast cause is known.
        logger.info {
            "[diag] NotificationSseService.onAchievementUnlocked fired: discordId=${event.discordId} " +
                "code=${event.achievementCode} name=${event.name}"
        }
        registry.fanOut(
            key = event.discordId,
            eventName = ACHIEVEMENT_EVENT,
            payload = mapOf(
                "title" to "${event.icon ?: DEFAULT_ACHIEVEMENT_ICON} Achievement unlocked — ${event.name}",
                "body" to event.description,
                "deepLink" to "/profile/${event.guildId}",
                "type" to "success",
            ),
        )
    }

    @EventListener
    fun onLevelUp(event: LevelUpEvent) {
        // TODO(diag-removal): drop alongside the other [diag] lines.
        logger.info {
            "[diag] NotificationSseService.onLevelUp fired: discordId=${event.discordId} " +
                "guildId=${event.guildId} newLevel=${event.newLevel}"
        }
        registry.fanOut(
            key = event.discordId,
            eventName = LEVEL_UP_EVENT,
            payload = mapOf(
                "title" to "Level ${event.newLevel}!",
                "body" to "You levelled up.",
                "deepLink" to "/profile/${event.guildId}",
                "type" to "success",
            ),
        )
    }

    @EventListener
    fun onTipSent(event: TipSentEvent) {
        // Recipient gets the toast — the sender triggered the tip and
        // doesn't need UI feedback they didn't ask for.
        // TODO(diag-removal): drop alongside the other [diag] lines.
        logger.info {
            "[diag] NotificationSseService.onTipSent fired: senderId=${event.senderDiscordId} " +
                "recipientId=${event.recipientDiscordId} guildId=${event.guildId} amount=${event.amount}"
        }
        registry.fanOut(
            key = event.recipientDiscordId,
            eventName = TIP_EVENT,
            payload = mapOf(
                "title" to "+${event.amount} credits",
                "body" to "You received a tip.",
                "deepLink" to "/profile/${event.guildId}",
                "type" to "success",
            ),
        )
    }

    @EventListener
    fun onLotteryDrawnForTicketHolder(event: LotteryDrawnForTicketHolderEvent) {
        // TODO(diag-removal): drop alongside the other [diag] lines.
        logger.info {
            "[diag] NotificationSseService.onLotteryDrawnForTicketHolder fired: discordId=${event.discordId} " +
                "guildId=${event.guildId} didWin=${event.didWin} amountWon=${event.amountWon}"
        }
        val (title, body) = if (event.didWin) {
            "🎰 You won the lottery!" to "Payout: ${event.amountWon} credits."
        } else {
            "🎟️ Lottery drew" to "Better luck next draw."
        }
        registry.fanOut(
            key = event.discordId,
            eventName = LOTTERY_DRAWN_EVENT,
            payload = mapOf(
                "title" to title,
                "body" to body,
                "deepLink" to "/profile/${event.guildId}",
                "didWin" to event.didWin,
                "amountWon" to event.amountWon,
                "type" to if (event.didWin) "success" else "info",
            ),
        )
    }

    /**
     * Proxy heartbeat so idle SSE connections don't get torn down by
     * intermediaries (e.g. Heroku's 55s idle timeout). Mirrors
     * `MusicSseService.heartbeat`.
     */
    @Scheduled(fixedRate = 15_000)
    fun heartbeat() {
        registry.heartbeat()
    }

    companion object {
        const val ACHIEVEMENT_EVENT = "achievement"
        const val LEVEL_UP_EVENT = "levelUp"
        const val TIP_EVENT = "tip"
        const val LOTTERY_DRAWN_EVENT = "lotteryDrawn"
        const val DEFAULT_ACHIEVEMENT_ICON = "🏅"

        // TODO(diag-removal): drop alongside the [diag] log lines.
        private val logger = DiscordLogger(NotificationSseService::class.java)
    }
}
