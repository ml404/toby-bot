package common.events.casino.baccarat

/**
 * Fact emitted by `BaccaratService` whenever a hand resolves in the
 * player's favour (their side beats the other; pushes and losses
 * stay silent). Subscribers (achievements) unlock the
 * `baccarat_first_win` catalog entry.
 */
data class BaccaratWonEvent(
    val discordId: Long,
    val guildId: Long,
)
