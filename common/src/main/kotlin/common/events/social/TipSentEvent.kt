package common.events.social

/**
 * Fact emitted by `TipService.tip` after a successful peer-to-peer
 * transfer. Mirrors [LevelUpEvent] / [StreakClaimedEvent]: synchronous
 * publication via [org.springframework.context.ApplicationEventPublisher];
 * subscribers (notification, achievements) react out-of-band.
 *
 * Only an `Ok` outcome publishes — daily-cap-exceeded and rejected
 * outcomes do not. Subscribers can therefore treat every event as a
 * confirmed transfer.
 */
data class TipSentEvent(
    val senderDiscordId: Long,
    val recipientDiscordId: Long,
    val guildId: Long,
    val amount: Long
)
