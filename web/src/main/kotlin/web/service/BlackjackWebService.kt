package web.service

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.bestTotal
import database.blackjack.canSplit
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
    private val memberLookup: MemberLookupHelper,
) {

    data class TableSummaryView(
        val tableId: Long,
        val hostDiscordId: Long?,
        /** Resolved Discord display name of the host, or fallback when not resolvable. */
        val hostName: String,
        val mode: String,
        val seats: Int,
        val maxSeats: Int,
        val phase: String,
        val handNumber: Long,
        val ante: Long,
    )

    data class SeatView(
        val discordId: Long,
        /** Discord display name (server nickname or username), or fallback when not in guild. */
        val displayName: String,
        /** CDN avatar URL, null when the bot can't resolve the member or they've left. */
        val avatarUrl: String?,
        val ante: Long,
        val stake: Long,
        val doubled: Boolean,
        val status: String,
        val pendingLeave: Boolean,
        /**
         * The seat's currently-active hand cards. For non-split seats
         * this is just the dealt 2-card (then hit-into-N-card) hand.
         * For split seats it's whichever sub-hand is being played right
         * now; the full split breakdown lives on [hands] and
         * [activeHandIndex].
         */
        val hand: List<String>,
        val total: Int,
        val soft: Boolean,
        /**
         * One entry per split branch (always ≥1). Same shape as the
         * legacy single-hand view extended per slot, so the JS
         * renderer can iterate this directly when [hands].size > 1.
         */
        val hands: List<HandSlotView> = emptyList(),
        val activeHandIndex: Int = 0,
    )

    data class HandSlotView(
        val cards: List<String>,
        val total: Int,
        val soft: Boolean,
        val stake: Long,
        val doubled: Boolean,
        val status: String,
        val fromSplit: Boolean,
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
        /** True iff the active hand is a 2-card pair the viewer can SPLIT. */
        val canSplit: Boolean,
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
        /** One entry per (seat, split-branch) pair. Empty for v1 callers. */
        val perHandResults: List<PerHandResultView> = emptyList(),
    )

    data class PerHandResultView(
        val discordId: Long,
        val handIndex: Int,
        val cards: List<String>,
        val total: Int,
        val stake: Long,
        val doubled: Boolean,
        val fromSplit: Boolean,
        val result: String,
        val payout: Long,
    )

    fun listMultiTables(guildId: Long): List<TableSummaryView> {
        val tables = tableRegistry.listForGuild(guildId)
            .filter { it.mode == BlackjackTable.Mode.MULTI }
        // Batch-resolve every host's display name once so the lobby doesn't
        // hit JDA per row.
        val hostIds = tables.mapNotNull { it.hostDiscordId }.toSet()
        val hosts = memberLookup.resolveAll(guildId, hostIds)
        return tables.map { table ->
            val host = table.hostDiscordId?.let { hosts[it] }
            TableSummaryView(
                tableId = table.id,
                hostDiscordId = table.hostDiscordId,
                hostName = host?.name
                    ?: table.hostDiscordId?.let { memberLookup.fallbackName(it) }
                    ?: "—",
                mode = table.mode.name,
                seats = table.seats.size,
                maxSeats = table.maxSeats,
                phase = table.phase.name,
                handNumber = table.handNumber,
                ante = table.ante,
            )
        }.sortedBy { it.tableId }
    }

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
            val canSplitNow = isMyTurn && mySeat != null &&
                !mySeat.doubled &&
                canSplit(mySeat.hand) &&
                mySeat.hands.size < Blackjack.MAX_SPLIT_HANDS

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

            // Batch-resolve every seat's Discord display info up front so the per-seat
            // map below is a pure projection (and so we hit JDA once per snapshot,
            // not once per seat).
            val members = memberLookup.resolveAll(table.guildId, table.seats.map { it.discordId })

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
                    val member = members[seat.discordId]
                    SeatView(
                        discordId = seat.discordId,
                        displayName = member?.name ?: memberLookup.fallbackName(seat.discordId),
                        avatarUrl = member?.avatarUrl,
                        ante = seat.ante,
                        stake = seat.stake,
                        doubled = seat.doubled,
                        status = seat.status.name,
                        pendingLeave = seat.pendingLeave,
                        hand = seat.hand.map(Card::toString),
                        total = bestTotal(seat.hand),
                        soft = isSoft(seat.hand),
                        hands = seat.hands.map { slot ->
                            HandSlotView(
                                cards = slot.cards.map(Card::toString),
                                total = bestTotal(slot.cards),
                                soft = isSoft(slot.cards),
                                stake = slot.stake,
                                doubled = slot.doubled,
                                status = slot.status.name,
                                fromSplit = slot.fromSplit,
                            )
                        },
                        activeHandIndex = seat.activeHandIndex,
                    )
                },
                dealer = dealerVisible,
                dealerTotalVisible = dealerTotalVisible,
                mySeatIndex = mySeatIndex,
                isMyTurn = isMyTurn,
                canDouble = canDouble,
                canSplit = canSplitNow,
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
                        perHandResults = result.perHandResults.map { perHand ->
                            PerHandResultView(
                                discordId = perHand.discordId,
                                handIndex = perHand.handIndex,
                                cards = perHand.cards.map(Card::toString),
                                total = perHand.total,
                                stake = perHand.stake,
                                doubled = perHand.doubled,
                                fromSplit = perHand.fromSplit,
                                result = perHand.result.name,
                                payout = perHand.payout,
                            )
                        },
                    )
                }
            )
        }
    }

    companion object {
        private const val HOLE_MASK = "??"
    }
}
