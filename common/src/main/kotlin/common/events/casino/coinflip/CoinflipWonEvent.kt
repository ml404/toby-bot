package common.events.casino.coinflip

/**
 * Fact emitted by `CoinflipService` whenever a flip lands on the
 * player's predicted side. Subscribers (achievements) unlock the
 * `coinflip_first_win` catalog entry.
 */
data class CoinflipWonEvent(
    val discordId: Long,
    val guildId: Long,
)
