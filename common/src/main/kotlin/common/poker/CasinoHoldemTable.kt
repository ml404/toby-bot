package common.poker

import common.card.Card
import common.card.Deck
import java.time.Instant

/**
 * Mutable state of a single Casino Hold'em table. One table per active
 * hand — there's no lobby phase or multi-seat support, so the layout is
 * deliberately flatter than [common.blackjack.BlackjackTable] /
 * [PokerTable].
 *
 * All mutations happen inside a monitor on the table object (see
 * [CasinoHoldemTableRegistry.lockTable]) so concurrent button clicks
 * serialise around the hand state.
 *
 * Lifecycle:
 *   1. `dealSolo` builds the table in [Phase.AWAIT_DECISION] with
 *      `playerHole`, `dealerHole`, the 3-card flop on `board`, and
 *      `pendingTurn` / `pendingRiver` stashed for the eventual CALL.
 *      The player's `stake` has already been debited at this point.
 *   2. On FOLD: phase → [Phase.RESOLVED], `lastResult` populated with
 *      a folded snapshot, ante is forfeited.
 *   3. On CALL: pending cards are appended to the board, the engine
 *      resolves the hand, phase → [Phase.RESOLVED], `lastResult`
 *      populated with the showdown breakdown.
 */
class CasinoHoldemTable(
    val id: Long,
    val guildId: Long,
    val playerDiscordId: Long,
    val stake: Long,
    var phase: Phase = Phase.AWAIT_DECISION,
    var deck: Deck? = null,
    val playerHole: MutableList<Card> = mutableListOf(),
    val dealerHole: MutableList<Card> = mutableListOf(),
    val board: MutableList<Card> = mutableListOf(),
    /**
     * Turn card peeled at deal time but not exposed to the player
     * until they CALL. Stashed on the table so the deal is fully
     * deterministic at the deal step (the deck is consumed once,
     * up-front) and the CALL transition is a simple appending move.
     */
    var pendingTurn: Card? = null,
    var pendingRiver: Card? = null,
    var lastResult: HandResult? = null,
    var lastActivityAt: Instant = Instant.now(),
) {

    enum class Phase {
        /** Cards dealt, flop visible, player owes a CALL/FOLD. */
        AWAIT_DECISION,
        /** Hand settled (folded or showdown). [lastResult] is populated. */
        RESOLVED,
    }

    /**
     * Snapshot of how a Casino Hold'em hand resolved. [resolution] is
     * `null` on a fold (no showdown happened); otherwise it carries
     * the ante / call breakdown produced by [CasinoHoldem.resolve].
     *
     * `callStake` is `0L` on a fold or a non-qualified-dealer push of
     * the call leg (matters for the `at-risk` math the embed surfaces).
     * Wait — non-qualified pushes still post the call stake to the
     * pot; it just gets refunded. To keep the math uniform we record
     * the call stake whenever the player CALL'd, regardless of whether
     * the leg ended PUSH or LOSE; only FOLD leaves it at zero.
     */
    data class HandResult(
        val playerHole: List<Card>,
        val dealerHole: List<Card>,
        val board: List<Card>,
        val resolution: CasinoHoldem.Resolution?,
        val folded: Boolean,
        val anteStake: Long,
        val callStake: Long,
        val antePayout: Long,
        val callPayout: Long,
        val totalPayout: Long,
        val resolvedAt: Instant,
    ) {
        val totalAtRisk: Long get() = anteStake + callStake
        val net: Long get() = totalPayout - totalAtRisk
    }
}
