package common.events.casino

/**
 * Fact emitted by `CasinoEdgeService` on every play of a luck game
 * (coinflip, dice, slots, plinko, wheel-of-fortune) — win or lose — since
 * those all route their RNG outcome through the shared bot-edge pass.
 * Subscribers (achievements) unlock the one-shot `casino_first_game`
 * milestone on a player's first such play, celebrating new members the
 * moment they actually try the casino (the install launcher funnels them
 * straight into a coinflip).
 */
data class CasinoGamePlayedEvent(
    val discordId: Long,
    val guildId: Long,
)
