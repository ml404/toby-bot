package common.events

/**
 * Fact emitted by `ScratchService` whenever a card resolves to the
 * 9-of-a-kind STAR jackpot. Subscribers (achievements) unlock the
 * `scratch_first_jackpot` catalog entry.
 */
data class ScratchJackpotEvent(
    val discordId: Long,
    val guildId: Long,
)
