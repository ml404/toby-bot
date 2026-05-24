package common.events.pvp.connect4

/**
 * Fact emitted by `Connect4Service` when a Connect 4 match resolves
 * to a winner — either someone completed a 4-in-a-row or one player
 * forfeited / timed out and the other took the walkover. Draws do
 * NOT publish (no winner to credit, no loser to penalise).
 *
 * Mirrors [TicTacToeResolvedEvent] / [RpsResolvedEvent]'s shape so
 * the PvP mini-games look the same to downstream handlers
 * (achievements).
 *
 * `pot` is `2 * stake - lossTribute` for wager matches; `0` for
 * free-play matches. Free-play wins still fire so `first_connect4_win`
 * unlocks regardless of whether anyone bet.
 */
data class Connect4ResolvedEvent(
    val winnerDiscordId: Long,
    val loserDiscordId: Long,
    val guildId: Long,
    val stake: Long,
    val pot: Long,
)
