package common.events

/**
 * Fact emitted by `BlackjackService` whenever a player slot resolves to
 * [database.blackjack.Blackjack.Result.PLAYER_BLACKJACK]. That result is
 * only produced by `evaluate()` on a two-card hand, so it always means a
 * natural-on-the-deal win (not a 21 reached after a hit, and not a
 * split-hand 21 — both of which return PLAYER_WIN instead).
 *
 * Subscribers (achievements) unlock the `blackjack_natural` catalog
 * entry. One event per natural seat in a multi-table resolution.
 */
data class BlackjackNaturalEvent(
    val discordId: Long,
    val guildId: Long,
)
