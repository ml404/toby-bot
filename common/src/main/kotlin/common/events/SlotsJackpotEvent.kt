package common.events

/**
 * Fact emitted by `SlotsService` whenever a spin lands the 100× STAR
 * three-of-a-kind jackpot pull. Subscribers (achievements) unlock the
 * `slots_first_jackpot` catalog entry.
 */
data class SlotsJackpotEvent(
    val discordId: Long,
    val guildId: Long,
)
