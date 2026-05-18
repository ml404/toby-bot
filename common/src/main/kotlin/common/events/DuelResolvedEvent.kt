package common.events

/**
 * Fact emitted by `DuelService.acceptDuel` after a duel resolves to a
 * winner. Only the `Win` outcome publishes — declined/timed-out duels
 * never reach a resolved state.
 *
 * Subscribers (achievements) can increment per-winner counters
 * (`first_duel_win`, `duel_wins_10`) without needing to read the
 * duel-log table.
 */
data class DuelResolvedEvent(
    val winnerDiscordId: Long,
    val loserDiscordId: Long,
    val guildId: Long,
    val stake: Long,
    val pot: Long
)
