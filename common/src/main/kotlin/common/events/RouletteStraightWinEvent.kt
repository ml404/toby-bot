package common.events

/**
 * Fact emitted by `RouletteService` whenever a `Roulette.Bet.STRAIGHT`
 * single-number bet wins. Subscribers (achievements) unlock the
 * `roulette_first_straight_win` catalog entry.
 */
data class RouletteStraightWinEvent(
    val discordId: Long,
    val guildId: Long,
)
