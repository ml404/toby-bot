package common.events

/**
 * Fact emitted by `PlinkoService` whenever a drop lands the top
 * multiplier for the chosen risk profile. Subscribers (achievements)
 * unlock the `plinko_first_jackpot` catalog entry.
 */
data class PlinkoJackpotEvent(
    val discordId: Long,
    val guildId: Long,
)
