package common.events.pvp.tictactoe

/**
 * Fact emitted by `TicTacToeService` when a Tic-Tac-Toe match resolves
 * to a winner — either someone lined up three in a row or one player
 * forfeited / timed out and the other took the walkover. Draws do NOT
 * publish (no winner to credit, no loser to penalise).
 *
 * Mirrors [RpsResolvedEvent]'s shape so the PvP mini-games look the
 * same to downstream handlers (achievements).
 *
 * `pot` is `2 * stake - lossTribute` for wager matches; `0` for
 * free-play matches. Free-play wins still fire so `first_tictactoe_win`
 * unlocks regardless of whether anyone bet.
 */
data class TicTacToeResolvedEvent(
    val winnerDiscordId: Long,
    val loserDiscordId: Long,
    val guildId: Long,
    val stake: Long,
    val pot: Long,
)
