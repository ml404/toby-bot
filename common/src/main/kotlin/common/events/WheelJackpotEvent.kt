package common.events

/**
 * Fact emitted by `WheelOfFortuneService` whenever a spin both lands
 * on the player's pick and that pick is the wheel's top multiplier
 * (10×). Subscribers (achievements) unlock the `wheel_first_jackpot`
 * catalog entry.
 */
data class WheelJackpotEvent(
    val discordId: Long,
    val guildId: Long,
)
