package web.service

import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.bestTotal
import database.blackjack.isSoft
import database.card.Card
import org.springframework.stereotype.Service

/**
 * Read-only projection of [BlackjackTable] for the web UI. Crucially,
 * the dealer's hole card is masked server-side during PLAYER_TURNS so
 * even a hostile client polling the JSON endpoint can't peek before
 * the dealer reveals.
 */
@Service
class BlackjackWebService(
    private val tableRegistry: BlackjackTableRegistry,
) {

    data class TableSummaryView(
        val tableId: Long,
        val hostDiscordId: Long?,
        val mode: String,
        val seats: Int,
        val maxSeats: Int,
        val phase: String,
        val handNumber: Long,
        val ante: Long,
    )

    data class SeatView(
        val discordId: Long,
        val ante: Long,
        val stake: Long,
        val doubled: Boolean,
        val status: String,
        val pendingLeave: Boolean,
        val hand: List<String>,
        val total: Int,
        val soft: Boolean,
    )

    data class TableStateView(
        val tableId: Long,
        val guildId: Long,
        val mode: String,
        val hostDiscordId: Long?,
        val phase: String,
        val handNumber: Long,
        val ante: Long,
        val maxSeats: Int,
        val actorIndex: Int,
        val seats: List<SeatView>,
        /** Dealer cards as currently visible — hole card masked when in PLAYER_TURNS. */
        val dealer: List<String>,
        val dealerTotalVisible: Int,
        val mySeatIndex: Int?,
        val isMyTurn: Boolean,
        val canDouble: Boolean,
        val shotClockSeconds: Int,
        val currentActorDeadlineEpochMillis: Long?,
        val lastResult: HandResultView?,
    )

    data class HandResultView(
        val handNumber: Long,
        val dealer: List<String>,
        val dealerTotal: Int,
        val seatResults: Map<String, String>,
        val payouts: Map<String, Long>,
        val pot: Long,
        val rake: Long,
    )

    fun listMultiTables(guildId: Long): List<TableSummaryView> =
        tableRegistry.listForGuild(guildId)
            .filter { it.mode == BlackjackTable.Mode.MULTI }
            .map { table ->
                TableSummaryView(
                    tableId = table.id,
                    hostDiscordId = table.hostDiscordId,
                    mode = table.mode.name,
                    seats = table.seats.size,
                    maxSeats = table.maxSeats,
                    phase = table.phase.name,
                    handNumber = table.handNumber,
                    ante = table.ante,
                )
            }
            .sortedBy { it.tableId }

    fun snapshot(tableId: Long, viewerDiscordId: Long): TableStateView? {
        val table = tableRegistry.get(tableId) ?: return null
        synchronized(table) {
            val mySeatIndex = table.seats.indexOfFirst { it.discordId == viewerDiscordId }
                .takeIf { it >= 0 }
            val mySeat = mySeatIndex?.let { table.seats[it] }
            val isMyTurn = mySeatIndex != null &&
                mySeatIndex == table.actorIndex &&
                table.phase == BlackjackTable.Phase.PLAYER_TURNS &&
                mySeat?.status == BlackjackTable.SeatStatus.ACTIVE
            val canDouble = isMyTurn && mySeat != null &&
                mySeat.hand.size == 2 && !mySeat.doubled

            // Mask dealer hole card while players are still acting.
            val dealerVisible = if (table.phase == BlackjackTable.Phase.PLAYER_TURNS && table.dealer.size > 1) {
                listOf(table.dealer.first().toString(), HOLE_MASK)
            } else {
                table.dealer.map(Card::toString)
            }
            val dealerTotalVisible = if (table.phase == BlackjackTable.Phase.PLAYER_TURNS && table.dealer.size > 1) {
                bestTotal(listOf(table.dealer.first()))
            } else {
                bestTotal(table.dealer)
            }

            return TableStateView(
                tableId = table.id,
                guildId = table.guildId,
                mode = table.mode.name,
                hostDiscordId = table.hostDiscordId,
                phase = table.phase.name,
                handNumber = table.handNumber,
                ante = table.ante,
                maxSeats = table.maxSeats,
                actorIndex = table.actorIndex,
                seats = table.seats.map { seat ->
                    SeatView(
                        discordId = seat.discordId,
                        ante = seat.ante,
                        stake = seat.stake,
                        doubled = seat.doubled,
                        status = seat.status.name,
                        pendingLeave = seat.pendingLeave,
                        hand = seat.hand.map(Card::toString),
                        total = bestTotal(seat.hand),
                        soft = isSoft(seat.hand),
                    )
                },
                dealer = dealerVisible,
                dealerTotalVisible = dealerTotalVisible,
                mySeatIndex = mySeatIndex,
                isMyTurn = isMyTurn,
                canDouble = canDouble,
                shotClockSeconds = table.shotClockSeconds,
                currentActorDeadlineEpochMillis = table.currentActorDeadline?.toEpochMilli(),
                lastResult = table.lastResult?.let { result ->
                    HandResultView(
                        handNumber = result.handNumber,
                        dealer = result.dealer.map(Card::toString),
                        dealerTotal = result.dealerTotal,
                        seatResults = result.seatResults.mapKeys { it.key.toString() }
                            .mapValues { it.value.name },
                        payouts = result.payouts.mapKeys { it.key.toString() },
                        pot = result.pot,
                        rake = result.rake,
                    )
                }
            )
        }
    }

    companion object {
        private const val HOLE_MASK = "??"
    }
}
