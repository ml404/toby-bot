package database.poker

import database.poker.PokerTable.Phase
import database.poker.PokerTable.SeatStatus
import java.time.Instant
import kotlin.random.Random

/**
 * Pure state-machine over [PokerTable]. Mutates the table in place but
 * holds no Spring or DB dependencies — easily unit-testable with a
 * seeded [Random] (mirrors `DuelService(random = ...)`).
 *
 * Fixed-limit Texas Hold'em flow: post blinds → deal 2 hole cards →
 * pre-flop bet → flop + bet → turn + bet → river + bet → showdown. Bet
 * size is `smallBet` pre-flop/flop and `bigBet` turn/river. Raises are
 * capped per street.
 *
 * v1 simplifications (see docstring on [PokerService]):
 *   - Single pot, no side pots. An all-in short stack can win the whole
 *     pot at showdown, which is incorrect poker but acceptable for a
 *     casual cash table.
 *   - "Call for less" is rejected; the player must fold instead. This
 *     keeps the pot trivially divisible and avoids partial calls.
 */
object PokerEngine {

    sealed interface PokerAction {
        data object Fold : PokerAction
        data object Check : PokerAction
        data object Call : PokerAction
        data object Raise : PokerAction
    }

    sealed interface ApplyResult {
        data class Applied(val event: ActionEvent) : ApplyResult
        data class Rejected(val reason: RejectReason) : ApplyResult
    }

    sealed interface ActionEvent {
        data object Continued : ActionEvent
        data class StreetAdvanced(val newPhase: Phase, val community: List<Card>) : ActionEvent
        data class HandResolved(val result: PokerTable.HandResult) : ActionEvent
    }

    enum class RejectReason {
        NO_HAND_IN_PROGRESS,
        NOT_AT_TABLE,
        NOT_YOUR_TURN,
        ILLEGAL_CHECK,
        ILLEGAL_CALL,
        ILLEGAL_RAISE,
        RAISE_CAP_REACHED,
        INSUFFICIENT_CHIPS_TO_CALL,
        INSUFFICIENT_CHIPS_TO_RAISE
    }

    sealed interface StartResult {
        data class Started(val handNumber: Long) : StartResult
        data object NotEnoughPlayers : StartResult
        data object HandAlreadyInProgress : StartResult
    }

    /**
     * Begin a new hand: rotate dealer, post blinds, deal hole cards,
     * set the first actor. Requires the table to be in [Phase.WAITING]
     * with at least 2 chip-holding seats. Idempotent guards return
     * sealed [StartResult] variants without mutating the table.
     */
    fun startHand(
        table: PokerTable,
        random: Random = Random.Default,
        now: Instant = Instant.now()
    ): StartResult {
        if (table.phase != Phase.WAITING) return StartResult.HandAlreadyInProgress
        val playable = table.seats.withIndex().filter { it.value.chips > 0 }
        if (playable.size < 2) return StartResult.NotEnoughPlayers

        table.handNumber++
        table.phase = Phase.PRE_FLOP
        table.deck = Deck(random)
        table.community.clear()
        table.pot = 0L
        table.currentBet = 0L
        table.raisesThisStreet = 0
        table.lastActivityAt = now
        table.lastResult = null

        for (seat in table.seats) {
            seat.committedThisRound = 0L
            seat.totalCommittedThisHand = 0L
            seat.holeCards = emptyList()
            seat.status = if (seat.chips > 0) SeatStatus.ACTIVE else SeatStatus.SITTING_OUT
        }

        // Rotate dealer button to the next chip-holding seat.
        table.dealerIndex = nextActiveIndex(table, table.dealerIndex)

        val active = activeIndicesInOrder(table)
        val sbIndex: Int
        val bbIndex: Int
        val firstActorIndex: Int
        if (active.size == 2) {
            // Heads-up: dealer is SB and acts first pre-flop.
            sbIndex = table.dealerIndex
            bbIndex = nextActiveIndex(table, sbIndex)
            firstActorIndex = sbIndex
        } else {
            sbIndex = nextActiveIndex(table, table.dealerIndex)
            bbIndex = nextActiveIndex(table, sbIndex)
            firstActorIndex = nextActiveIndex(table, bbIndex)
        }

        postBlind(table, sbIndex, table.smallBlind)
        postBlind(table, bbIndex, table.bigBlind)
        table.currentBet = table.bigBlind

        // Deal two hole cards to each active seat starting left of the dealer.
        val deck = table.deck!!
        val dealOrder = activeIndicesStartingFrom(table, nextActiveIndex(table, table.dealerIndex))
        repeat(2) {
            for (i in dealOrder) {
                table.seats[i].holeCards = table.seats[i].holeCards + deck.deal()
            }
        }

        table.actorIndex = firstActorIndex
        table.seatsToAct = countNotFoldedNotAllIn(table)
        return StartResult.Started(table.handNumber)
    }

    /**
     * Apply [action] for [discordId] at the current street. Rejects with
     * [RejectReason] if the action is illegal. On success, returns
     * either [ActionEvent.Continued] (street still in progress),
     * [ActionEvent.StreetAdvanced] (round closed, dealt next street),
     * or [ActionEvent.HandResolved] (hand fully settled — chips already
     * credited to winners, [PokerTable.HandResult] populated, table
     * back in [Phase.WAITING]).
     */
    fun applyAction(
        table: PokerTable,
        discordId: Long,
        action: PokerAction,
        rakeRate: Double,
        now: Instant = Instant.now()
    ): ApplyResult {
        if (table.phase == Phase.WAITING) return ApplyResult.Rejected(RejectReason.NO_HAND_IN_PROGRESS)
        val seatIndex = table.seats.indexOfFirst { it.discordId == discordId }
        if (seatIndex < 0) return ApplyResult.Rejected(RejectReason.NOT_AT_TABLE)
        if (seatIndex != table.actorIndex) return ApplyResult.Rejected(RejectReason.NOT_YOUR_TURN)
        val seat = table.seats[seatIndex]
        if (seat.status != SeatStatus.ACTIVE) return ApplyResult.Rejected(RejectReason.NOT_YOUR_TURN)

        val betUnit = currentBetUnit(table)
        when (action) {
            PokerAction.Fold -> {
                seat.status = SeatStatus.FOLDED
                // The folded seat no longer needs a decision this round.
                table.seatsToAct = (table.seatsToAct - 1).coerceAtLeast(0)
            }
            PokerAction.Check -> {
                if (seat.committedThisRound != table.currentBet) {
                    return ApplyResult.Rejected(RejectReason.ILLEGAL_CHECK)
                }
                table.seatsToAct = (table.seatsToAct - 1).coerceAtLeast(0)
            }
            PokerAction.Call -> {
                val owe = table.currentBet - seat.committedThisRound
                if (owe <= 0L) return ApplyResult.Rejected(RejectReason.ILLEGAL_CALL)
                if (seat.chips < owe) {
                    return ApplyResult.Rejected(RejectReason.INSUFFICIENT_CHIPS_TO_CALL)
                }
                commit(table, seat, owe)
                if (seat.chips == 0L) seat.status = SeatStatus.ALL_IN
                table.seatsToAct = (table.seatsToAct - 1).coerceAtLeast(0)
            }
            PokerAction.Raise -> {
                if (table.raisesThisStreet >= table.maxRaisesPerStreet) {
                    return ApplyResult.Rejected(RejectReason.RAISE_CAP_REACHED)
                }
                val owe = table.currentBet - seat.committedThisRound
                val total = owe + betUnit
                if (seat.chips < total) {
                    return ApplyResult.Rejected(RejectReason.INSUFFICIENT_CHIPS_TO_RAISE)
                }
                commit(table, seat, total)
                table.currentBet += betUnit
                table.raisesThisStreet++
                if (seat.chips == 0L) seat.status = SeatStatus.ALL_IN
                // Everyone else who can still act needs another decision.
                table.seatsToAct = countNotFoldedNotAllIn(table) - 1
            }
        }

        table.lastActivityAt = now

        // Short-circuit: only one non-folded seat? They take the pot uncontested.
        val nonFolded = table.seats.filter { it.status != SeatStatus.FOLDED && it.status != SeatStatus.SITTING_OUT }
        if (nonFolded.size <= 1) {
            return ApplyResult.Applied(ActionEvent.HandResolved(resolveHand(table, rakeRate, now)))
        }

        // Round still has actors to bet?
        val canStillBet = nonFolded.any { it.status == SeatStatus.ACTIVE }
        if (table.seatsToAct > 0 && canStillBet) {
            advanceActor(table)
            return ApplyResult.Applied(ActionEvent.Continued)
        }

        // Round closed — advance street or resolve.
        return advanceStreet(table, rakeRate, now)
    }

    private fun advanceStreet(
        table: PokerTable,
        rakeRate: Double,
        now: Instant
    ): ApplyResult {
        val deck = table.deck ?: return ApplyResult.Rejected(RejectReason.NO_HAND_IN_PROGRESS)

        // Sweep round commitments into the pot via committedThisRound reset.
        for (seat in table.seats) seat.committedThisRound = 0L
        table.currentBet = 0L
        table.raisesThisStreet = 0

        val nextPhase = when (table.phase) {
            Phase.PRE_FLOP -> {
                table.community.addAll(deck.deal(3)); Phase.FLOP
            }
            Phase.FLOP -> {
                table.community.add(deck.deal()); Phase.TURN
            }
            Phase.TURN -> {
                table.community.add(deck.deal()); Phase.RIVER
            }
            Phase.RIVER -> {
                return ApplyResult.Applied(ActionEvent.HandResolved(resolveHand(table, rakeRate, now)))
            }
            Phase.WAITING -> return ApplyResult.Rejected(RejectReason.NO_HAND_IN_PROGRESS)
        }
        table.phase = nextPhase

        // If everyone left in the hand is all-in, no more betting possible —
        // run out the rest of the board and resolve in one shot.
        val activeBetters = table.seats.count { it.status == SeatStatus.ACTIVE }
        if (activeBetters <= 1) {
            while (table.phase != Phase.RIVER) {
                when (table.phase) {
                    Phase.FLOP -> { table.community.add(deck.deal()); table.phase = Phase.TURN }
                    Phase.TURN -> { table.community.add(deck.deal()); table.phase = Phase.RIVER }
                    else -> Unit
                }
            }
            return ApplyResult.Applied(ActionEvent.HandResolved(resolveHand(table, rakeRate, now)))
        }

        // First actor postflop: first ACTIVE seat left of the dealer.
        table.actorIndex = nextActiveIndex(table, table.dealerIndex)
        table.seatsToAct = countNotFoldedNotAllIn(table)
        return ApplyResult.Applied(ActionEvent.StreetAdvanced(nextPhase, table.community.toList()))
    }

    private fun resolveHand(
        table: PokerTable,
        rakeRate: Double,
        now: Instant
    ): PokerTable.HandResult {
        val pot = table.pot
        val rake = (pot * rakeRate).toLong().coerceAtLeast(0L).coerceAtMost(pot)
        val distributable = pot - rake

        val contenders = table.seats.filter {
            it.status == SeatStatus.ACTIVE || it.status == SeatStatus.ALL_IN
        }

        val winners: List<PokerTable.Seat> = when {
            contenders.size == 1 -> listOf(contenders.single())
            else -> {
                val ranked = contenders.map { it to HandEvaluator.bestHand(it.holeCards, table.community) }
                val best = ranked.maxOf { it.second }
                ranked.filter { it.second.compareTo(best) == 0 }.map { it.first }
            }
        }

        // Even split, with the remainder going to the first listed winner so
        // total chip count is preserved exactly.
        val payouts = mutableMapOf<Long, Long>()
        if (winners.isNotEmpty()) {
            val share = distributable / winners.size
            val remainder = distributable - share * winners.size
            for ((i, winner) in winners.withIndex()) {
                val take = share + if (i == 0) remainder else 0L
                winner.chips += take
                payouts[winner.discordId] = take
            }
        }

        val revealedHoleCards: Map<Long, List<Card>> =
            if (contenders.size > 1) contenders.associate { it.discordId to it.holeCards } else emptyMap()

        val result = PokerTable.HandResult(
            handNumber = table.handNumber,
            winners = winners.map { it.discordId },
            payoutByDiscordId = payouts.toMap(),
            pot = pot,
            rake = rake,
            board = table.community.toList(),
            revealedHoleCards = revealedHoleCards,
            resolvedAt = now
        )

        // Reset table for the next hand.
        table.phase = Phase.WAITING
        table.pot = 0L
        table.currentBet = 0L
        table.raisesThisStreet = 0
        table.community.clear()
        table.deck = null
        for (seat in table.seats) {
            seat.committedThisRound = 0L
            seat.totalCommittedThisHand = 0L
            seat.holeCards = emptyList()
            seat.status = if (seat.chips > 0) SeatStatus.SITTING_OUT else SeatStatus.SITTING_OUT
        }
        table.lastActivityAt = now
        table.lastResult = result
        return result
    }

    private fun postBlind(table: PokerTable, seatIndex: Int, amount: Long) {
        val seat = table.seats[seatIndex]
        val toPost = minOf(seat.chips, amount)
        commit(table, seat, toPost)
        if (seat.chips == 0L) seat.status = SeatStatus.ALL_IN
    }

    private fun commit(table: PokerTable, seat: PokerTable.Seat, amount: Long) {
        seat.chips -= amount
        seat.committedThisRound += amount
        seat.totalCommittedThisHand += amount
        table.pot += amount
    }

    private fun currentBetUnit(table: PokerTable): Long = when (table.phase) {
        Phase.PRE_FLOP, Phase.FLOP -> table.smallBet
        Phase.TURN, Phase.RIVER -> table.bigBet
        Phase.WAITING -> table.smallBet
    }

    private fun advanceActor(table: PokerTable) {
        table.actorIndex = nextSeatToAct(table, table.actorIndex)
    }

    private fun nextSeatToAct(table: PokerTable, fromIndex: Int): Int {
        val n = table.seats.size
        if (n == 0) return -1
        var i = (fromIndex + 1) % n
        repeat(n) {
            val seat = table.seats[i]
            if (seat.status == SeatStatus.ACTIVE) return i
            i = (i + 1) % n
        }
        return fromIndex
    }

    private fun nextActiveIndex(table: PokerTable, fromIndex: Int): Int {
        val n = table.seats.size
        if (n == 0) return -1
        var i = (fromIndex + 1) % n
        repeat(n) {
            val seat = table.seats[i]
            if (seat.status == SeatStatus.ACTIVE || seat.status == SeatStatus.ALL_IN) return i
            i = (i + 1) % n
        }
        return fromIndex
    }

    private fun activeIndicesInOrder(table: PokerTable): List<Int> =
        table.seats.withIndex()
            .filter { it.value.status == SeatStatus.ACTIVE || it.value.status == SeatStatus.ALL_IN }
            .map { it.index }

    private fun activeIndicesStartingFrom(table: PokerTable, startIndex: Int): List<Int> {
        val n = table.seats.size
        val out = mutableListOf<Int>()
        var i = startIndex
        repeat(n) {
            val seat = table.seats[i]
            if (seat.status == SeatStatus.ACTIVE || seat.status == SeatStatus.ALL_IN) {
                out.add(i)
            }
            i = (i + 1) % n
        }
        return out
    }

    private fun countNotFoldedNotAllIn(table: PokerTable): Int =
        table.seats.count { it.status == SeatStatus.ACTIVE }
}
