package common.events.casino.highlow

/**
 * Fact emitted by `HighlowService` whenever a hand resolves — wins
 * and losses both fire. The achievement handler uses the `isWin`
 * flag to drive a streak counter through the existing achievement
 * progress mechanism (`progress(delta=1)` on win, `setProgress(0)`
 * on loss) so the `highlow_first_streak` 5-in-a-row achievement
 * works without any extra DB schema.
 */
data class HighlowHandResolvedEvent(
    val discordId: Long,
    val guildId: Long,
    val isWin: Boolean,
)
