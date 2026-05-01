package web.service

import database.poker.Card
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import org.springframework.stereotype.Service

/**
 * Read-only projection of [PokerTable] for the web UI. Crucially, the
 * caller's own hole cards are returned face-up while every other
 * player's hole cards are masked server-side — so even if a hostile
 * client reads the JSON snapshot directly, they can't see the rest of
 * the table's cards. This is enforced here, not in the template, so
 * tests can assert it.
 */
@Service
class PokerWebService(
    private val tableRegistry: PokerTableRegistry,
) {

    data class TableSummaryView(
        val tableId: Long,
        val hostDiscordId: Long,
        val seats: Int,
        val maxSeats: Int,
        val phase: String,
        val handNumber: Long,
        val minBuyIn: Long,
        val maxBuyIn: Long,
        /** v2-7: free-play flag. Used by the lobby table list to render a "🆓 Free" tag. */
        val isFreePlay: Boolean = false,
    )

    data class TableStateView(
        val tableId: Long,
        val guildId: Long,
        val hostDiscordId: Long,
        val phase: String,
        val handNumber: Long,
        val pot: Long,
        val currentBet: Long,
        val raisesThisStreet: Int,
        val maxRaisesPerStreet: Int,
        val smallBlind: Long,
        val bigBlind: Long,
        val smallBet: Long,
        val bigBet: Long,
        val minBuyIn: Long,
        val maxBuyIn: Long,
        val maxSeats: Int,
        val dealerIndex: Int,
        val actorIndex: Int,
        val community: List<String>,
        val seats: List<SeatView>,
        val mySeatIndex: Int?,
        val myHoleCards: List<String>,
        val isMyTurn: Boolean,
        val canCheck: Boolean,
        val canCall: Boolean,
        val canRaise: Boolean,
        val callAmount: Long,
        val raiseAmount: Long,
        val lastResult: HandResultView?,
        /**
         * v2 (PR #v2-2): shot-clock visibility for the polling JS.
         * `shotClockSeconds = 0` → clock is disabled for this table.
         * `currentActorDeadlineEpochMillis = null` → no actor decision
         * is pending right now (waiting for next hand). The client
         * computes the live remaining seconds so a slow poll cycle
         * doesn't stall the countdown.
         */
        val shotClockSeconds: Int = 0,
        val currentActorDeadlineEpochMillis: Long? = null,
        /**
         * v2-7: free-play flag from [PokerTable.isFreePlay]. The
         * frontend renders a "🆓 Free play" badge and hides
         * jackpot-related affordances when set; service-side wallet
         * mutations are already short-circuited.
         */
        val isFreePlay: Boolean = false,
    )

    data class SeatView(
        val discordId: Long,
        val chips: Long,
        val committedThisRound: Long,
        val totalCommittedThisHand: Long,
        val status: String,
        /** Other players' cards are always masked; only the viewer sees their own. */
        val holeCards: List<String>,
    )

    data class HandResultView(
        val handNumber: Long,
        val winners: List<Long>,
        val payoutByDiscordId: Map<String, Long>,
        val pot: Long,
        val rake: Long,
        val board: List<String>,
        val revealedHoleCards: Map<String, List<String>>,
        /**
         * v2 (PR #v2-1): per-tier breakdown of the pot. For a hand with
         * no all-ins this is exactly one entry covering the full pot.
         * Multi-way all-ins at different stack sizes split into a main
         * pot + N side pots so the renderer can label each tier with
         * its eligible-player set.
         */
        val pots: List<PotResultView> = emptyList(),
        /**
         * Discord IDs to chips that came back unraked because no other
         * seat could match the over-commitment (standard "uncalled bet
         * returned" rule).
         */
        val refundedByDiscordId: Map<String, Long> = emptyMap(),
    )

    data class PotResultView(
        val cap: Long,
        val amount: Long,
        val eligibleDiscordIds: List<Long>,
        val winners: List<Long>,
        val payoutByDiscordId: Map<String, Long>,
    )

    fun listGuildTables(guildId: Long): List<TableSummaryView> =
        tableRegistry.listForGuild(guildId).map {
            TableSummaryView(
                tableId = it.id,
                hostDiscordId = it.hostDiscordId,
                seats = it.seats.size,
                maxSeats = it.maxSeats,
                phase = it.phase.name,
                handNumber = it.handNumber,
                minBuyIn = it.minBuyIn,
                maxBuyIn = it.maxBuyIn,
                isFreePlay = it.isFreePlay,
            )
        }.sortedBy { it.tableId }

    fun snapshot(tableId: Long, viewerDiscordId: Long): TableStateView? {
        val table = tableRegistry.get(tableId) ?: return null
        synchronized(table) {
            val mySeatIndex = table.seats.indexOfFirst { it.discordId == viewerDiscordId }.takeIf { it >= 0 }
            val mySeat = mySeatIndex?.let { table.seats[it] }
            val isMyTurn = mySeatIndex != null &&
                mySeatIndex == table.actorIndex &&
                table.phase != PokerTable.Phase.WAITING &&
                mySeat?.status == PokerTable.SeatStatus.ACTIVE

            val betUnit = currentBetUnit(table)
            val owe: Long
            val canCall: Boolean
            val canCheck: Boolean
            val canRaise: Boolean
            if (mySeat != null && isMyTurn) {
                owe = (table.currentBet - mySeat.committedThisRound).coerceAtLeast(0L)
                canCall = owe > 0L && mySeat.chips >= owe
                canCheck = owe == 0L
                canRaise = table.raisesThisStreet < table.maxRaisesPerStreet && mySeat.chips >= owe + betUnit
            } else {
                owe = if (mySeat != null) (table.currentBet - mySeat.committedThisRound).coerceAtLeast(0L) else 0L
                canCall = false
                canCheck = false
                canRaise = false
            }

            return TableStateView(
                tableId = table.id,
                guildId = table.guildId,
                hostDiscordId = table.hostDiscordId,
                phase = table.phase.name,
                handNumber = table.handNumber,
                pot = table.pot,
                currentBet = table.currentBet,
                raisesThisStreet = table.raisesThisStreet,
                maxRaisesPerStreet = table.maxRaisesPerStreet,
                smallBlind = table.smallBlind,
                bigBlind = table.bigBlind,
                smallBet = table.smallBet,
                bigBet = table.bigBet,
                minBuyIn = table.minBuyIn,
                maxBuyIn = table.maxBuyIn,
                maxSeats = table.maxSeats,
                dealerIndex = table.dealerIndex,
                actorIndex = table.actorIndex,
                community = table.community.map(Card::toString),
                seats = table.seats.mapIndexed { idx, seat ->
                    SeatView(
                        discordId = seat.discordId,
                        chips = seat.chips,
                        committedThisRound = seat.committedThisRound,
                        totalCommittedThisHand = seat.totalCommittedThisHand,
                        status = seat.status.name,
                        // Server-side mask: only the viewer's own cards are returned.
                        holeCards = if (idx == mySeatIndex) seat.holeCards.map(Card::toString) else emptyList()
                    )
                },
                mySeatIndex = mySeatIndex,
                myHoleCards = mySeat?.holeCards?.map(Card::toString) ?: emptyList(),
                isMyTurn = isMyTurn,
                canCheck = canCheck,
                canCall = canCall,
                canRaise = canRaise,
                callAmount = owe,
                raiseAmount = owe + betUnit,
                shotClockSeconds = table.shotClockSeconds,
                currentActorDeadlineEpochMillis = table.currentActorDeadline?.toEpochMilli(),
                isFreePlay = table.isFreePlay,
                lastResult = table.lastResult?.let { result ->
                    HandResultView(
                        handNumber = result.handNumber,
                        winners = result.winners,
                        payoutByDiscordId = result.payoutByDiscordId.mapKeys { it.key.toString() },
                        pot = result.pot,
                        rake = result.rake,
                        board = result.board.map(Card::toString),
                        revealedHoleCards = result.revealedHoleCards
                            .mapKeys { it.key.toString() }
                            .mapValues { e -> e.value.map(Card::toString) },
                        pots = result.pots.map { p ->
                            PotResultView(
                                cap = p.cap,
                                amount = p.amount,
                                eligibleDiscordIds = p.eligibleDiscordIds,
                                winners = p.winners,
                                payoutByDiscordId = p.payoutByDiscordId.mapKeys { it.key.toString() },
                            )
                        },
                        refundedByDiscordId = result.refundedByDiscordId.mapKeys { it.key.toString() },
                    )
                }
            )
        }
    }

    private fun currentBetUnit(table: PokerTable): Long = when (table.phase) {
        PokerTable.Phase.PRE_FLOP, PokerTable.Phase.FLOP -> table.smallBet
        PokerTable.Phase.TURN, PokerTable.Phase.RIVER -> table.bigBet
        PokerTable.Phase.WAITING -> table.smallBet
    }
}
