package common.events.casino.horseracing

/**
 * Fact emitted by `HorseRacingService` whenever a race resolves to a
 * win for the player (their picked horse finishes consistent with
 * their bet type). Subscribers (achievements) unlock the
 * `horse_racing_first_win` catalog entry.
 */
data class HorseRacingWonEvent(
    val discordId: Long,
    val guildId: Long,
)
