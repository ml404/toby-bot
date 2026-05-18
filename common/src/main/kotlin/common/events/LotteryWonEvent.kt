package common.events

/**
 * Fact emitted by `JackpotLotteryService` for each winner of a daily
 * lottery draw (both WEIGHTED and NUMBER_MATCH modes). One event per
 * winner — subscribers (achievements, future "you won!" DMs) react per
 * recipient.
 */
data class LotteryWonEvent(
    val discordId: Long,
    val guildId: Long,
    val amount: Long
)
