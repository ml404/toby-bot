package common.events.casino.dice

/**
 * Fact emitted by `DiceService` whenever a roll hits the player's
 * prediction. Subscribers (achievements) unlock the `dice_first_win`
 * catalog entry.
 */
data class DiceWonEvent(
    val discordId: Long,
    val guildId: Long,
)
