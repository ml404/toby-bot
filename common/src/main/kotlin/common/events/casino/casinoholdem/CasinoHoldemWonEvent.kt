package common.events.casino.casinoholdem

/**
 * Fact emitted by `CasinoHoldemService` whenever at least one leg
 * (ante or call) of a hand resolves in the player's favour. A pure
 * push or fold/lose stays silent. Subscribers (achievements) unlock
 * the `casino_holdem_first_win` catalog entry.
 */
data class CasinoHoldemWonEvent(
    val discordId: Long,
    val guildId: Long,
)
