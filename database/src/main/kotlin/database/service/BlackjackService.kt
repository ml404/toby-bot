package database.service

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.bestTotal
import database.blackjack.isBlackjack
import database.blackjack.isBust
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.random.Random

/**
 * Atomic play path for both `/blackjack solo` and `/blackjack` multi
 * (create/join/start/leave/action). Uses [BlackjackTableRegistry]
 * (shared in-memory book of tables) and the [Blackjack] rules engine.
 *
 * Lifecycle of a player's chips, mirroring [PokerService]:
 *   - SOLO deal — debits stake from `socialCredit` into seat escrow,
 *     then resolves either immediately (natural blackjack / dealer-also-
 *     blackjack push) or after a Hit/Stand/Double sequence.
 *   - MULTI create / join — debits the table's ante into seat escrow.
 *     The pot is materialised at [startMultiHand] from the seated
 *     antes; on hand resolution it's split among qualifying winners and
 *     a fixed rake is routed to the per-guild jackpot pool.
 *   - leave (multi, between hands) — refunds the seat's escrow.
 *   - idle eviction — refunds every seat's escrow via [evictAllSeats].
 *
 * SOLO settlement also feeds the existing [JackpotHelper] win-roll /
 * loss-tribute hooks so blackjack participates in the same per-guild
 * jackpot pool as the rest of the casino.
 */
@Service
class BlackjackService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val tableRegistry: BlackjackTableRegistry,
    private val blackjack: Blackjack = Blackjack(),
    private val random: Random = Random.Default
) {

    sealed interface SoloDealOutcome {
        /** Hand dealt; player must act. [snapshot] is a live reference — embeds should read it under [BlackjackTableRegistry.lockTable] if precise consistency is needed. */
        data class Dealt(val tableId: Long, val snapshot: BlackjackTable) : SoloDealOutcome
        /** Natural blackjack short-circuit (player BJ vs non-BJ dealer, or both BJ → push). */
        data class Resolved(
            val tableId: Long,
            val result: BlackjackTable.HandResult,
            val newBalance: Long,
            val jackpotPayout: Long,
            val lossTribute: Long
        ) : SoloDealOutcome
        data class InvalidStake(val min: Long, val max: Long) : SoloDealOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : SoloDealOutcome
        data object UnknownUser : SoloDealOutcome
    }

    sealed interface SoloActionOutcome {
        data class Continued(val snapshot: BlackjackTable) : SoloActionOutcome
        data class Resolved(
            val tableId: Long,
            val result: BlackjackTable.HandResult,
            val newBalance: Long,
            val jackpotPayout: Long,
            val lossTribute: Long
        ) : SoloActionOutcome
        data object HandNotFound : SoloActionOutcome
        data object NotYourHand : SoloActionOutcome
        data object IllegalAction : SoloActionOutcome
        data class InsufficientCreditsForDouble(val needed: Long, val have: Long) : SoloActionOutcome
    }

    sealed interface MultiCreateOutcome {
        data class Ok(val tableId: Long) : MultiCreateOutcome
        data class InvalidAnte(val min: Long, val max: Long) : MultiCreateOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : MultiCreateOutcome
        data object UnknownUser : MultiCreateOutcome
    }

    sealed interface MultiJoinOutcome {
        data class Ok(val seatIndex: Int, val newBalance: Long) : MultiJoinOutcome
        data object TableNotFound : MultiJoinOutcome
        data object TableFull : MultiJoinOutcome
        data object AlreadySeated : MultiJoinOutcome
        data object HandInProgress : MultiJoinOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : MultiJoinOutcome
        data object UnknownUser : MultiJoinOutcome
    }

    sealed interface MultiLeaveOutcome {
        data class Ok(val refund: Long, val newBalance: Long) : MultiLeaveOutcome
        data object TableNotFound : MultiLeaveOutcome
        data object NotSeated : MultiLeaveOutcome
        data object HandInProgress : MultiLeaveOutcome
    }

    sealed interface MultiStartOutcome {
        data class Ok(val handNumber: Long) : MultiStartOutcome
        data object TableNotFound : MultiStartOutcome
        data object NotHost : MultiStartOutcome
        data object NotEnoughPlayers : MultiStartOutcome
        data object HandAlreadyInProgress : MultiStartOutcome
    }

    sealed interface MultiActionOutcome {
        data class Continued(val snapshot: BlackjackTable) : MultiActionOutcome
        data class HandResolved(val tableId: Long, val result: BlackjackTable.HandResult) : MultiActionOutcome
        data object TableNotFound : MultiActionOutcome
        data object NotSeated : MultiActionOutcome
        data object NotYourTurn : MultiActionOutcome
        data object NoHandInProgress : MultiActionOutcome
        data object IllegalAction : MultiActionOutcome
        data class InsufficientCreditsForDouble(val needed: Long, val have: Long) : MultiActionOutcome
    }

    /**
     * Self-injection so registry callbacks (which are invoked from a
     * scheduler thread holding `this`, not the Spring proxy) still
     * route `@Transactional` methods through the proxy. Mirrors
     * [PokerService]'s pattern.
     */
    @Autowired
    @Lazy
    private var self: BlackjackService? = null

    private val transactionalSelf: BlackjackService get() = self ?: this

    @PostConstruct
    fun wireRegistry() {
        tableRegistry.setOnIdleEvict { table -> transactionalSelf.evictAllSeats(table) }
    }

    // -------------------------------------------------------------------------
    // SOLO
    // -------------------------------------------------------------------------

    @Transactional
    fun dealSolo(discordId: Long, guildId: Long, stake: Long): SoloDealOutcome {
        val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Blackjack.MIN_STAKE, Blackjack.MAX_STAKE
        )
        val ok = when (check) {
            is BalanceCheck.InvalidStake -> return SoloDealOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> return SoloDealOutcome.UnknownUser
            is BalanceCheck.Insufficient -> return SoloDealOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> check
        }
        // Debit stake into seat escrow.
        ok.user.socialCredit = ok.balance - stake
        userService.updateUser(ok.user)

        val table = tableRegistry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = discordId,
            ante = stake,
            maxSeats = 1
        )
        val initial = tableRegistry.lockTable(table.id) { t ->
            val deck = blackjack.newDeck()
            t.deck = deck
            val deal = blackjack.dealStartingHands(deck)
            val seat = BlackjackTable.Seat(
                discordId = discordId,
                hand = deal.player,
                ante = stake,
                stake = stake,
                doubled = false,
                status = if (isBlackjack(deal.player)) BlackjackTable.SeatStatus.BLACKJACK else BlackjackTable.SeatStatus.ACTIVE
            )
            t.seats.add(seat)
            t.dealer.addAll(deal.dealer)
            t.handNumber = 1L
            t.lastActivityAt = Instant.now()

            // Natural-BJ short-circuit: either side has BJ → resolve now without a player turn.
            val playerHasBJ = isBlackjack(seat.hand)
            val dealerHasBJ = isBlackjack(t.dealer)
            if (playerHasBJ || dealerHasBJ) {
                t.phase = BlackjackTable.Phase.RESOLVED
                val result = blackjack.evaluate(seat.hand, t.dealer)
                seat.status = statusFor(result, seat.status)
                ResolveDecision.ResolveImmediately(seat, result)
            } else {
                t.phase = BlackjackTable.Phase.PLAYER_TURNS
                ResolveDecision.AwaitPlayer
            }
        } ?: return SoloDealOutcome.UnknownUser

        return when (initial) {
            ResolveDecision.AwaitPlayer -> SoloDealOutcome.Dealt(
                tableId = table.id,
                snapshot = table
            )
            is ResolveDecision.ResolveImmediately -> {
                val settlement = settleSolo(table, initial.seat, initial.result, guildId)
                SoloDealOutcome.Resolved(
                    tableId = table.id,
                    result = settlement.handResult,
                    newBalance = settlement.newBalance,
                    jackpotPayout = settlement.jackpotPayout,
                    lossTribute = settlement.lossTribute
                )
            }
        }
    }

    private sealed interface ResolveDecision {
        data object AwaitPlayer : ResolveDecision
        data class ResolveImmediately(
            val seat: BlackjackTable.Seat,
            val result: Blackjack.Result
        ) : ResolveDecision
    }

    @Transactional
    fun applySoloAction(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        action: Blackjack.Action
    ): SoloActionOutcome {
        val table = tableRegistry.get(tableId) ?: return SoloActionOutcome.HandNotFound
        if (table.guildId != guildId) return SoloActionOutcome.HandNotFound
        if (table.mode != BlackjackTable.Mode.SOLO) return SoloActionOutcome.HandNotFound

        // For DOUBLE, debit the additional stake from the wallet BEFORE
        // taking the table monitor — keeps the credit lock outside the
        // table lock to match the ordering used elsewhere. The follow-up
        // action lockTable still re-validates seat ownership and phase
        // under the monitor before committing the draw.
        if (action == Blackjack.Action.DOUBLE) {
            val seatPreview = tableRegistry.lockTable(tableId) { t ->
                val seat = t.seats.firstOrNull() ?: return@lockTable DoubleCheck.Illegal
                if (seat.discordId != discordId) return@lockTable DoubleCheck.Illegal
                if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable DoubleCheck.Illegal
                if (seat.hand.size != 2 || seat.doubled) return@lockTable DoubleCheck.Illegal
                DoubleCheck.Ok(seat.ante)
            } ?: return SoloActionOutcome.HandNotFound
            when (seatPreview) {
                is DoubleCheck.Illegal -> return SoloActionOutcome.IllegalAction
                is DoubleCheck.Ok -> {
                    val user = userService.getUserByIdForUpdate(discordId, guildId)
                        ?: return SoloActionOutcome.HandNotFound
                    val balance = user.socialCredit ?: 0L
                    if (balance < seatPreview.amount) {
                        return SoloActionOutcome.InsufficientCreditsForDouble(seatPreview.amount, balance)
                    }
                    user.socialCredit = balance - seatPreview.amount
                    userService.updateUser(user)
                }
            }
        }

        val transition = tableRegistry.lockTable(tableId) { t ->
            val seat = t.seats.firstOrNull() ?: return@lockTable SoloTransition.HandNotFound
            if (seat.discordId != discordId) return@lockTable SoloTransition.NotYourHand
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable SoloTransition.IllegalAction
            val deck = t.deck ?: return@lockTable SoloTransition.HandNotFound

            when (action) {
                Blackjack.Action.HIT -> {
                    blackjack.hit(seat.hand, deck)
                    if (isBust(seat.hand)) {
                        seat.status = BlackjackTable.SeatStatus.BUSTED
                        t.phase = BlackjackTable.Phase.RESOLVED
                        SoloTransition.Resolve(seat, blackjack.evaluate(seat.hand, t.dealer))
                    } else if (bestTotal(seat.hand) == 21) {
                        // 21 from a hit auto-stands — no further decisions.
                        seat.status = BlackjackTable.SeatStatus.STANDING
                        playOutAndResolve(t, seat)
                    } else {
                        t.lastActivityAt = Instant.now()
                        SoloTransition.Continue
                    }
                }
                Blackjack.Action.STAND -> {
                    seat.status = BlackjackTable.SeatStatus.STANDING
                    playOutAndResolve(t, seat)
                }
                Blackjack.Action.DOUBLE -> {
                    // The debit already happened; mark the seat doubled
                    // and draw exactly one card, then play out dealer.
                    seat.doubled = true
                    seat.stake = seat.ante * 2
                    blackjack.hit(seat.hand, deck)
                    seat.status = if (isBust(seat.hand)) BlackjackTable.SeatStatus.BUSTED
                    else BlackjackTable.SeatStatus.DOUBLED
                    if (seat.status == BlackjackTable.SeatStatus.BUSTED) {
                        t.phase = BlackjackTable.Phase.RESOLVED
                        SoloTransition.Resolve(seat, blackjack.evaluate(seat.hand, t.dealer))
                    } else {
                        playOutAndResolve(t, seat)
                    }
                }
            }
        } ?: return SoloActionOutcome.HandNotFound

        return when (transition) {
            SoloTransition.HandNotFound -> SoloActionOutcome.HandNotFound
            SoloTransition.NotYourHand -> SoloActionOutcome.NotYourHand
            SoloTransition.IllegalAction -> SoloActionOutcome.IllegalAction
            SoloTransition.Continue -> SoloActionOutcome.Continued(table)
            is SoloTransition.Resolve -> {
                transition.seat.status = statusFor(transition.result, transition.seat.status)
                val settlement = settleSolo(table, transition.seat, transition.result, guildId)
                SoloActionOutcome.Resolved(
                    tableId = table.id,
                    result = settlement.handResult,
                    newBalance = settlement.newBalance,
                    jackpotPayout = settlement.jackpotPayout,
                    lossTribute = settlement.lossTribute
                )
            }
        }
    }

    /**
     * Helper invoked under the table monitor: dealer plays out then we
     * evaluate. Mutates [t] and [seat]; returns a [SoloTransition.Resolve]
     * the caller turns into a settlement.
     */
    private fun playOutAndResolve(
        t: BlackjackTable,
        seat: BlackjackTable.Seat
    ): SoloTransition {
        val deck = t.deck ?: return SoloTransition.HandNotFound
        t.phase = BlackjackTable.Phase.DEALER_TURN
        blackjack.playOutDealer(t.dealer, deck)
        t.phase = BlackjackTable.Phase.RESOLVED
        return SoloTransition.Resolve(seat, blackjack.evaluate(seat.hand, t.dealer))
    }

    private sealed interface SoloTransition {
        data object HandNotFound : SoloTransition
        data object NotYourHand : SoloTransition
        data object IllegalAction : SoloTransition
        data object Continue : SoloTransition
        data class Resolve(
            val seat: BlackjackTable.Seat,
            val result: Blackjack.Result
        ) : SoloTransition
    }

    private sealed interface DoubleCheck {
        data class Ok(val amount: Long) : DoubleCheck
        data object Illegal : DoubleCheck
    }

    private data class SoloSettlement(
        val handResult: BlackjackTable.HandResult,
        val newBalance: Long,
        val jackpotPayout: Long,
        val lossTribute: Long
    )

    /**
     * Credit the payout (multiplier × stake) back to the player and
     * route jackpot win-rolls / loss-tributes. Caller has already
     * marked the seat status and put the table in RESOLVED.
     */
    private fun settleSolo(
        table: BlackjackTable,
        seat: BlackjackTable.Seat,
        result: Blackjack.Result,
        guildId: Long
    ): SoloSettlement {
        val multiplier = blackjack.multiplier(result)
        val payout = (seat.stake * multiplier).toLong()

        val user = userService.getUserByIdForUpdate(seat.discordId, guildId)
        var newBalance: Long = user?.socialCredit ?: 0L
        if (user != null && payout > 0L) {
            user.socialCredit = newBalance + payout
            userService.updateUser(user)
            newBalance = user.socialCredit ?: newBalance
        }

        var jackpotPayout = 0L
        var lossTribute = 0L
        val isWin = result == Blackjack.Result.PLAYER_WIN || result == Blackjack.Result.PLAYER_BLACKJACK
        val isLoss = result == Blackjack.Result.DEALER_WIN || result == Blackjack.Result.PLAYER_BUST
        if (isWin && user != null) {
            jackpotPayout = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, user, guildId, random
            )
            if (jackpotPayout > 0L) newBalance = user.socialCredit ?: newBalance
        } else if (isLoss) {
            lossTribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, seat.stake)
        }

        val handResult = BlackjackTable.HandResult(
            handNumber = table.handNumber,
            dealer = table.dealer.toList(),
            dealerTotal = bestTotal(table.dealer),
            seatResults = mapOf(seat.discordId to result),
            payouts = if (payout > 0L) mapOf(seat.discordId to payout) else emptyMap(),
            pot = seat.stake,
            rake = 0L,
            resolvedAt = Instant.now()
        )
        synchronized(table) {
            table.lastResult = handResult
            table.lastActivityAt = Instant.now()
        }
        return SoloSettlement(handResult, newBalance, jackpotPayout, lossTribute)
    }

    private fun statusFor(
        result: Blackjack.Result,
        current: BlackjackTable.SeatStatus
    ): BlackjackTable.SeatStatus = when (result) {
        Blackjack.Result.PLAYER_BLACKJACK -> BlackjackTable.SeatStatus.BLACKJACK
        Blackjack.Result.PLAYER_BUST -> BlackjackTable.SeatStatus.BUSTED
        else -> current
    }

    /** Drop a SOLO table after the player has had a chance to read the result. */
    fun closeSoloTable(tableId: Long) {
        val table = tableRegistry.get(tableId) ?: return
        if (table.mode != BlackjackTable.Mode.SOLO) return
        if (table.phase == BlackjackTable.Phase.RESOLVED) {
            tableRegistry.remove(tableId)
        }
    }

    // -------------------------------------------------------------------------
    // MULTI
    // -------------------------------------------------------------------------

    @Transactional
    fun createMultiTable(
        hostDiscordId: Long,
        guildId: Long,
        ante: Long
    ): MultiCreateOutcome {
        val check = WagerHelper.checkAndLock(
            userService, hostDiscordId, guildId, ante,
            Blackjack.MULTI_MIN_ANTE, Blackjack.MULTI_MAX_ANTE
        )
        val ok = when (check) {
            is BalanceCheck.InvalidStake -> return MultiCreateOutcome.InvalidAnte(check.min, check.max)
            BalanceCheck.UnknownUser -> return MultiCreateOutcome.UnknownUser
            is BalanceCheck.Insufficient -> return MultiCreateOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> check
        }
        ok.user.socialCredit = ok.balance - ante
        userService.updateUser(ok.user)
        val table = tableRegistry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = hostDiscordId,
            ante = ante,
            maxSeats = Blackjack.MULTI_MAX_SEATS
        )
        synchronized(table) {
            table.seats.add(
                BlackjackTable.Seat(
                    discordId = hostDiscordId,
                    ante = ante,
                    stake = ante
                )
            )
        }
        return MultiCreateOutcome.Ok(table.id)
    }

    @Transactional
    fun joinMultiTable(
        discordId: Long,
        guildId: Long,
        tableId: Long
    ): MultiJoinOutcome {
        val table = tableRegistry.get(tableId) ?: return MultiJoinOutcome.TableNotFound
        if (table.guildId != guildId) return MultiJoinOutcome.TableNotFound
        if (table.mode != BlackjackTable.Mode.MULTI) return MultiJoinOutcome.TableNotFound

        val pre = synchronized(table) {
            when {
                table.phase != BlackjackTable.Phase.LOBBY -> JoinPreflight.HandInProgress
                table.seats.any { it.discordId == discordId } -> JoinPreflight.AlreadySeated
                table.seats.size >= table.maxSeats -> JoinPreflight.TableFull
                else -> JoinPreflight.Ok
            }
        }
        when (pre) {
            JoinPreflight.HandInProgress -> return MultiJoinOutcome.HandInProgress
            JoinPreflight.AlreadySeated -> return MultiJoinOutcome.AlreadySeated
            JoinPreflight.TableFull -> return MultiJoinOutcome.TableFull
            JoinPreflight.Ok -> Unit
        }

        val ante = table.ante
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return MultiJoinOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < ante) return MultiJoinOutcome.InsufficientCredits(ante, balance)

        val seatIndex = synchronized(table) {
            if (table.phase != BlackjackTable.Phase.LOBBY) return@synchronized -3
            if (table.seats.any { it.discordId == discordId }) return@synchronized -1
            if (table.seats.size >= table.maxSeats) return@synchronized -2
            table.seats.add(
                BlackjackTable.Seat(
                    discordId = discordId,
                    ante = ante,
                    stake = ante
                )
            )
            table.lastActivityAt = Instant.now()
            table.seats.size - 1
        }
        if (seatIndex == -1) return MultiJoinOutcome.AlreadySeated
        if (seatIndex == -2) return MultiJoinOutcome.TableFull
        if (seatIndex == -3) return MultiJoinOutcome.HandInProgress

        user.socialCredit = balance - ante
        userService.updateUser(user)
        return MultiJoinOutcome.Ok(seatIndex = seatIndex, newBalance = user.socialCredit ?: 0L)
    }

    @Transactional
    fun leaveMultiTable(
        discordId: Long,
        guildId: Long,
        tableId: Long
    ): MultiLeaveOutcome {
        val table = tableRegistry.get(tableId) ?: return MultiLeaveOutcome.TableNotFound
        if (table.guildId != guildId) return MultiLeaveOutcome.TableNotFound
        if (table.mode != BlackjackTable.Mode.MULTI) return MultiLeaveOutcome.TableNotFound

        val refund = synchronized(table) {
            if (table.phase != BlackjackTable.Phase.LOBBY) return@synchronized -1L
            val idx = table.seats.indexOfFirst { it.discordId == discordId }
            if (idx < 0) return@synchronized -2L
            val seat = table.seats.removeAt(idx)
            table.lastActivityAt = Instant.now()
            seat.stake
        }
        if (refund == -1L) return MultiLeaveOutcome.HandInProgress
        if (refund == -2L) return MultiLeaveOutcome.NotSeated

        val newBalance = if (refund > 0L) {
            val user = userService.getUserByIdForUpdate(discordId, guildId)
                ?: return MultiLeaveOutcome.NotSeated
            user.socialCredit = (user.socialCredit ?: 0L) + refund
            userService.updateUser(user)
            user.socialCredit ?: 0L
        } else {
            userService.getUserById(discordId, guildId)?.socialCredit ?: 0L
        }

        // Drop empty table.
        synchronized(table) {
            if (table.seats.isEmpty()) tableRegistry.remove(tableId)
        }
        return MultiLeaveOutcome.Ok(refund = refund, newBalance = newBalance)
    }

    fun startMultiHand(
        hostDiscordId: Long,
        guildId: Long,
        tableId: Long
    ): MultiStartOutcome {
        val table = tableRegistry.get(tableId) ?: return MultiStartOutcome.TableNotFound
        if (table.guildId != guildId) return MultiStartOutcome.TableNotFound
        if (table.mode != BlackjackTable.Mode.MULTI) return MultiStartOutcome.TableNotFound
        if (table.hostDiscordId != hostDiscordId) return MultiStartOutcome.NotHost

        return tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.LOBBY) return@lockTable MultiStartOutcome.HandAlreadyInProgress
            if (t.seats.size < Blackjack.MULTI_MIN_SEATS) return@lockTable MultiStartOutcome.NotEnoughPlayers

            // Reset per-hand seat state (cards/status), preserve identity & ante.
            for (seat in t.seats) {
                seat.hand = mutableListOf()
                seat.doubled = false
                seat.stake = seat.ante
                seat.status = BlackjackTable.SeatStatus.ACTIVE
            }
            t.dealer.clear()
            val deck = blackjack.newDeck()
            t.deck = deck
            // Deal 2 cards each, alternating per seat then dealer (cosmetic).
            repeat(2) {
                for (seat in t.seats) seat.hand.add(deck.deal())
                t.dealer.add(deck.deal())
            }
            // Mark naturals.
            for (seat in t.seats) {
                if (isBlackjack(seat.hand)) seat.status = BlackjackTable.SeatStatus.BLACKJACK
            }
            t.handNumber += 1L
            t.phase = BlackjackTable.Phase.PLAYER_TURNS
            t.actorIndex = nextActiveSeatIndex(t, fromInclusive = 0)
            t.lastActivityAt = Instant.now()

            // If every seat is naturally finished (everyone got BJ), proceed to settle.
            if (t.actorIndex < 0) {
                MultiStartOutcome.Ok(t.handNumber).also { resolveMultiHand(t, guildId) }
            } else {
                MultiStartOutcome.Ok(t.handNumber)
            }
        } ?: MultiStartOutcome.TableNotFound
    }

    @Transactional
    fun applyMultiAction(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        action: Blackjack.Action
    ): MultiActionOutcome {
        val table = tableRegistry.get(tableId) ?: return MultiActionOutcome.TableNotFound
        if (table.guildId != guildId) return MultiActionOutcome.TableNotFound
        if (table.mode != BlackjackTable.Mode.MULTI) return MultiActionOutcome.TableNotFound

        // For DOUBLE we need to debit the second ante outside the table monitor.
        if (action == Blackjack.Action.DOUBLE) {
            val pre = tableRegistry.lockTable(tableId) { t ->
                if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable MultiDoublePreflight.NoHand
                val actorSeat = t.seats.getOrNull(t.actorIndex) ?: return@lockTable MultiDoublePreflight.NoHand
                if (actorSeat.discordId != discordId) return@lockTable MultiDoublePreflight.NotYourTurn
                if (actorSeat.hand.size != 2 || actorSeat.doubled) return@lockTable MultiDoublePreflight.Illegal
                MultiDoublePreflight.Ok(actorSeat.ante)
            } ?: return MultiActionOutcome.TableNotFound
            when (pre) {
                MultiDoublePreflight.NoHand -> return MultiActionOutcome.NoHandInProgress
                MultiDoublePreflight.NotYourTurn -> return MultiActionOutcome.NotYourTurn
                MultiDoublePreflight.Illegal -> return MultiActionOutcome.IllegalAction
                is MultiDoublePreflight.Ok -> {
                    val user = userService.getUserByIdForUpdate(discordId, guildId)
                        ?: return MultiActionOutcome.NotSeated
                    val balance = user.socialCredit ?: 0L
                    if (balance < pre.amount) {
                        return MultiActionOutcome.InsufficientCreditsForDouble(pre.amount, balance)
                    }
                    user.socialCredit = balance - pre.amount
                    userService.updateUser(user)
                }
            }
        }

        val transition = tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable MultiTransition.NoHand
            val actorSeat = t.seats.getOrNull(t.actorIndex) ?: return@lockTable MultiTransition.NoHand
            if (t.seats.none { it.discordId == discordId }) return@lockTable MultiTransition.NotSeated
            if (actorSeat.discordId != discordId) return@lockTable MultiTransition.NotYourTurn
            val deck = t.deck ?: return@lockTable MultiTransition.NoHand

            when (action) {
                Blackjack.Action.HIT -> {
                    blackjack.hit(actorSeat.hand, deck)
                    if (isBust(actorSeat.hand)) {
                        actorSeat.status = BlackjackTable.SeatStatus.BUSTED
                        advanceActor(t)
                    } else if (bestTotal(actorSeat.hand) == 21) {
                        actorSeat.status = BlackjackTable.SeatStatus.STANDING
                        advanceActor(t)
                    }
                }
                Blackjack.Action.STAND -> {
                    actorSeat.status = BlackjackTable.SeatStatus.STANDING
                    advanceActor(t)
                }
                Blackjack.Action.DOUBLE -> {
                    actorSeat.doubled = true
                    actorSeat.stake = actorSeat.ante * 2
                    blackjack.hit(actorSeat.hand, deck)
                    actorSeat.status = if (isBust(actorSeat.hand)) BlackjackTable.SeatStatus.BUSTED
                    else BlackjackTable.SeatStatus.DOUBLED
                    advanceActor(t)
                }
            }
            t.lastActivityAt = Instant.now()

            if (t.phase == BlackjackTable.Phase.PLAYER_TURNS) {
                MultiTransition.Continue
            } else {
                MultiTransition.Resolve
            }
        } ?: return MultiActionOutcome.TableNotFound

        return when (transition) {
            MultiTransition.NoHand -> MultiActionOutcome.NoHandInProgress
            MultiTransition.NotSeated -> MultiActionOutcome.NotSeated
            MultiTransition.NotYourTurn -> MultiActionOutcome.NotYourTurn
            MultiTransition.Continue -> MultiActionOutcome.Continued(table)
            MultiTransition.Resolve -> {
                val result = tableRegistry.lockTable(tableId) { t ->
                    resolveMultiHand(t, guildId)
                } ?: return MultiActionOutcome.TableNotFound
                MultiActionOutcome.HandResolved(table.id, result)
            }
        }
    }

    private sealed interface MultiDoublePreflight {
        data class Ok(val amount: Long) : MultiDoublePreflight
        data object NoHand : MultiDoublePreflight
        data object NotYourTurn : MultiDoublePreflight
        data object Illegal : MultiDoublePreflight
    }

    private sealed interface MultiTransition {
        data object NoHand : MultiTransition
        data object NotSeated : MultiTransition
        data object NotYourTurn : MultiTransition
        data object Continue : MultiTransition
        data object Resolve : MultiTransition
    }

    /**
     * Pick the next ACTIVE seat after the current actor. If none exist
     * (everyone is finished), advance the table to DEALER_TURN.
     */
    private fun advanceActor(t: BlackjackTable) {
        val next = nextActiveSeatIndex(t, fromInclusive = t.actorIndex + 1)
        if (next < 0) {
            t.phase = BlackjackTable.Phase.DEALER_TURN
        } else {
            t.actorIndex = next
        }
    }

    private fun nextActiveSeatIndex(t: BlackjackTable, fromInclusive: Int): Int {
        for (i in fromInclusive until t.seats.size) {
            if (t.seats[i].status == BlackjackTable.SeatStatus.ACTIVE) return i
        }
        return -1
    }

    /**
     * Dealer plays out (if any seat survived and hasn't busted), then
     * we evaluate every seat against the dealer, build the pot, peel
     * off any pushed antes, route a fixed 5% rake to the jackpot pool,
     * and split the remainder among qualifying winners.
     *
     * Returns the [BlackjackTable.HandResult] and stamps the table back
     * to LOBBY for the next hand.
     */
    private fun resolveMultiHand(t: BlackjackTable, guildId: Long): BlackjackTable.HandResult {
        val deck = t.deck ?: error("missing deck on resolve")
        // Skip the dealer draw entirely if every seat is busted — saves
        // an irrelevant card flip and matches the "everyone bust" pot-
        // to-jackpot path below.
        val allBust = t.seats.all { it.status == BlackjackTable.SeatStatus.BUSTED }
        if (!allBust) {
            blackjack.playOutDealer(t.dealer, deck)
        }
        t.phase = BlackjackTable.Phase.RESOLVED

        val results = LinkedHashMap<Long, Blackjack.Result>()
        for (seat in t.seats) {
            results[seat.discordId] = blackjack.evaluate(seat.hand, t.dealer)
        }

        val totalPot = t.seats.sumOf { it.stake }
        val pushSeats = t.seats.filter { results[it.discordId] == Blackjack.Result.PUSH }
        val pushTotal = pushSeats.sumOf { it.stake }
        val winnerSeats = t.seats.filter {
            val r = results[it.discordId]
            r == Blackjack.Result.PLAYER_WIN || r == Blackjack.Result.PLAYER_BLACKJACK
        }
        val distributable = totalPot - pushTotal

        val payouts = LinkedHashMap<Long, Long>()
        // Refund pushed antes.
        for (seat in pushSeats) payouts[seat.discordId] = seat.stake

        var rake = 0L
        if (distributable > 0L) {
            if (winnerSeats.isEmpty()) {
                // No qualifiers — everything to the jackpot.
                rake = distributable
            } else {
                val baseRake = (distributable * Blackjack.MULTI_RAKE).toLong()
                val remaining = distributable - baseRake
                val perWinner = remaining / winnerSeats.size
                val dust = remaining - perWinner * winnerSeats.size
                for (seat in winnerSeats) {
                    payouts.merge(seat.discordId, perWinner) { a, b -> a + b }
                }
                rake = baseRake + dust
            }
        }
        if (rake > 0L) jackpotService.addToPool(guildId, rake)

        // Credit each payout under sorted-id locks to avoid deadlocks.
        if (payouts.isNotEmpty()) {
            val locked = userService.lockUsersInAscendingOrder(payouts.keys, guildId)
            for ((id, amount) in payouts) {
                if (amount <= 0L) continue
                val user = locked[id] ?: continue
                user.socialCredit = (user.socialCredit ?: 0L) + amount
                userService.updateUser(user)
            }
        }

        val handResult = BlackjackTable.HandResult(
            handNumber = t.handNumber,
            dealer = t.dealer.toList(),
            dealerTotal = bestTotal(t.dealer),
            seatResults = results,
            payouts = payouts,
            pot = totalPot,
            rake = rake,
            resolvedAt = Instant.now()
        )
        t.lastResult = handResult
        t.lastActivityAt = Instant.now()
        // Reset to LOBBY so the host can /blackjack start the next hand
        // with the same seats and antes.
        t.phase = BlackjackTable.Phase.LOBBY
        // Re-debit the next-hand ante from each surviving seat? No —
        // seat.stake is escrow held from the just-resolved hand, which
        // is already paid out. The seat retains its identity and must
        // re-ante before the next hand. Simpler: clear seat-state but
        // require a fresh ante. For v1, we drop seats that no longer
        // have an escrowed ante; players re-join via /blackjack join
        // for the next hand. This avoids a second wallet debit cycle
        // hidden behind /blackjack start.
        t.seats.clear()
        if (t.seats.isEmpty()) tableRegistry.remove(t.id)
        return handResult
    }

    // -------------------------------------------------------------------------
    // EVICT
    // -------------------------------------------------------------------------

    /**
     * Refund every seated player's escrowed stake to their wallet and
     * drop the table. Called by the registry's idle sweeper. Locks
     * users in ascending-id order to avoid cycles with concurrent
     * `/tip`, `/duel`, etc.
     */
    @Transactional
    fun evictAllSeats(table: BlackjackTable) {
        val refunds: Map<Long, Long> = synchronized(table) {
            val out = table.seats.filter { it.stake > 0L }.associate { it.discordId to it.stake }
            table.seats.clear()
            out
        }
        if (refunds.isEmpty()) return
        val locked = userService.lockUsersInAscendingOrder(refunds.keys, table.guildId)
        for ((id, amount) in refunds) {
            val user = locked[id] ?: continue
            user.socialCredit = (user.socialCredit ?: 0L) + amount
            userService.updateUser(user)
        }
    }

    private enum class JoinPreflight { Ok, AlreadySeated, TableFull, HandInProgress }

    fun snapshot(tableId: Long): BlackjackTable? = tableRegistry.get(tableId)
}
