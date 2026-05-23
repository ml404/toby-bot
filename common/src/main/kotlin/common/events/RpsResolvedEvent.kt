package common.events

/**
 * Fact emitted by `RpsService` when a Rock-Paper-Scissors match
 * resolves to a winner — either both players picked and one move beat
 * the other, or one player forfeited / timed out and the other took
 * the walkover. Draws and double-no-pick do NOT publish (no winner to
 * credit, no loser to penalise).
 *
 * Subscribers (achievements) tally per-winner / per-loser counters
 * without needing to read game-state. Mirrors [DuelResolvedEvent]'s
 * shape so the two PvP games look the same to downstream handlers.
 *
 * `pot` is `2 * stake - lossTribute` for wager matches; `0` for
 * free-play matches. Free-play wins still fire so `first_rps_win`
 * unlocks regardless of whether anyone bet.
 */
data class RpsResolvedEvent(
    val winnerDiscordId: Long,
    val loserDiscordId: Long,
    val guildId: Long,
    val stake: Long,
    val pot: Long,
)
