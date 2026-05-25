package web.service

import common.card.Card
import common.casino.casinoholdem.CasinoHoldem
import common.casino.casinoholdem.CasinoHoldemTable
import database.poker.CasinoHoldemTableRegistry
import org.springframework.stereotype.Service

/**
 * Read-only projection of [CasinoHoldemTable] for the web UI. Crucially,
 * the dealer's hole cards are masked server-side while the table is in
 * [CasinoHoldemTable.Phase.AWAIT_DECISION] so a hostile client polling
 * `/state` can't peek before showdown. Once the table is RESOLVED the
 * dealer is fully revealed (including on a fold — the dealer reveal on
 * a fold is intentionally suppressed for blackjack but Casino Hold'em
 * wagers don't depend on dealer's hole on a fold either way; we keep
 * the dealer hidden on folds to mirror the discord embed).
 */
@Service
class CasinoHoldemWebService(
    private val tableRegistry: CasinoHoldemTableRegistry,
) {

    data class TableStateView(
        val tableId: Long,
        val guildId: Long,
        // Stringified for parity with [BlackjackWebService.TableStateView] —
        // 18-digit Discord snowflakes survive JS's 53-bit Number precision.
        val playerDiscordId: String,
        val phase: String,
        val stake: Long,
        val callStake: Long,
        val playerHole: List<String>,
        /** Masked while AWAIT_DECISION or on a fold; full reveal on showdown. */
        val dealerHole: List<String>,
        val board: List<String>,
        val lastResult: HandResultView?,
    )

    data class HandResultView(
        val playerHole: List<String>,
        val dealerHole: List<String>,
        val board: List<String>,
        val folded: Boolean,
        val dealerQualified: Boolean,
        val anteResult: String?,
        val callResult: String?,
        val anteStake: Long,
        val callStake: Long,
        val antePayout: Long,
        val callPayout: Long,
        val totalPayout: Long,
        val net: Long,
    )

    fun snapshot(tableId: Long, viewerDiscordId: Long): TableStateView? {
        val table = tableRegistry.get(tableId) ?: return null
        if (table.playerDiscordId != viewerDiscordId) return null
        synchronized(table) {
            val masked = when (table.phase) {
                CasinoHoldemTable.Phase.AWAIT_DECISION -> List(table.dealerHole.size) { HOLE_MASK }
                CasinoHoldemTable.Phase.RESOLVED -> {
                    // Hide the dealer's hole on a fold so the player can't
                    // post-mortem the hand they walked away from.
                    if (table.lastResult?.folded == true) List(table.dealerHole.size) { HOLE_MASK }
                    else table.dealerHole.map(Card::toString)
                }
            }
            return TableStateView(
                tableId = table.id,
                guildId = table.guildId,
                playerDiscordId = table.playerDiscordId.toString(),
                phase = table.phase.name,
                stake = table.stake,
                callStake = table.stake * CasinoHoldem.CALL_MULTIPLE,
                playerHole = table.playerHole.map(Card::toString),
                dealerHole = masked,
                board = table.board.map(Card::toString),
                lastResult = table.lastResult?.let { resultView(it) },
            )
        }
    }

    private fun resultView(result: CasinoHoldemTable.HandResult): HandResultView = HandResultView(
        playerHole = result.playerHole.map(Card::toString),
        dealerHole = if (result.folded) List(result.dealerHole.size) { HOLE_MASK }
        else result.dealerHole.map(Card::toString),
        board = result.board.map(Card::toString),
        folded = result.folded,
        dealerQualified = result.resolution?.dealerQualified ?: false,
        anteResult = result.resolution?.anteResult?.name,
        callResult = result.resolution?.callResult?.name,
        anteStake = result.anteStake,
        callStake = result.callStake,
        antePayout = result.antePayout,
        callPayout = result.callPayout,
        totalPayout = result.totalPayout,
        net = result.net,
    )

    /** Look up the player's currently-active table id in [guildId], if any. */
    fun findActiveTable(guildId: Long, discordId: Long): Long? {
        val mine = tableRegistry.listForGuild(guildId)
            .filter { it.playerDiscordId == discordId }
        // Prefer a still-in-flight hand. After resolution the table sticks
        // around for one more `/state` poll so the UI can render the result;
        // a fresh deal sweeps it.
        return (mine.firstOrNull { it.phase != CasinoHoldemTable.Phase.RESOLVED }
            ?: mine.firstOrNull())?.id
    }

    companion object {
        private const val HOLE_MASK = "??"
    }
}
