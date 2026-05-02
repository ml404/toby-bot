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
        /** Between-hands leave: seat removed and any escrowed ante refunded immediately. */
        data class Ok(val refund: Long, val newBalance: Long) : MultiLeaveOutcome
        /**
         * Mid-hand leave: seat is flagged `pendingLeave` so the engine
         * auto-stands them on their turn and drops the seat as soon as
         * the hand resolves. [stakeHeld] is what's at risk this hand —
         * the seat still settles normally (winnings / loss / refund) at
         * end of hand, then is removed.
         */
        data class QueuedForEndOfHand(val stakeHeld: Long) : MultiLeaveOutcome
        /** Idempotent second click during the same hand. */
        data object AlreadyLeaving : MultiLeaveOutcome
        data object TableNotFound : MultiLeaveOutcome
        data object NotSeated : MultiLeaveOutcome
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
        tableRegistry.setOnShotClockExpired { table -> autoStandOnTimeout(table) }
    }

    /**
     * Invoked by [BlackjackTableRegistry] when an actor's shot clock
     * fires before they've acted. Auto-stands them on their behalf so
     * the hand can progress. Calls `transactionalSelf.applyMultiAction`
     * (not `this.applyMultiAction`) so the `@Transactional` boundary on
     * the action handler is honoured — scheduler-thread callbacks
     * otherwise bypass the proxy.
     */
    private fun autoStandOnTimeout(table: BlackjackTable) {
        val timedOut = synchronized(table) {
            if (table.phase != BlackjackTable.Phase.PLAYER_TURNS) return
            table.seats.getOrNull(table.actorIndex)?.discordId
        } ?: return
        transactionalSelf.applyMultiAction(timedOut, table.guildId, table.id, Blackjack.Action.STAND)
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
            maxSeats = Blackjack.MULTI_MAX_SEATS,
            shotClockSeconds = Blackjack.MULTI_SHOT_CLOCK_SECONDS
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

        // Mid-hand path: queue the leave so the engine auto-stands the
        // seat on its turn, then drops it once the hand resolves. Mirrors
        // PokerService.cashOut's pendingLeave flow.
        val midHand = synchronized(table) {
            if (table.phase == BlackjackTable.Phase.LOBBY) {
                MidHandOutcome.NotApplicable
            } else {
                val seatIdx = table.seats.indexOfFirst { it.discordId == discordId }
                val seat = if (seatIdx >= 0) table.seats[seatIdx] else null
                when {
                    seat == null -> MidHandOutcome.NotSeated
                    seat.pendingLeave -> MidHandOutcome.AlreadyLeaving
                    else -> {
                        seat.pendingLeave = true
                        MidHandOutcome.Queued(
                            stakeHeld = seat.stake,
                            isCurrentActor = seatIdx == table.actorIndex &&
                                seat.status == BlackjackTable.SeatStatus.ACTIVE
                        )
                    }
                }
            }
        }
        when (midHand) {
            is MidHandOutcome.NotSeated -> return MultiLeaveOutcome.NotSeated
            is MidHandOutcome.AlreadyLeaving -> return MultiLeaveOutcome.AlreadyLeaving
            is MidHandOutcome.Queued -> {
                // If it's the leaver's turn, drive an auto-stand straight
                // through the proxy so the rest of the table sees a normal
                // turn advance. Auto-stand cascades naturally past any
                // other pendingLeave seats via [applyMultiAction].
                if (midHand.isCurrentActor) {
                    transactionalSelf.applyMultiAction(
                        discordId, guildId, tableId, Blackjack.Action.STAND
                    )
                }
                return MultiLeaveOutcome.QueuedForEndOfHand(stakeHeld = midHand.stakeHeld)
            }
            MidHandOutcome.NotApplicable -> Unit // fall through to LOBBY path
        }

        // Between-hands path: existing instant cash-out.
        val refund = synchronized(table) {
            if (table.phase != BlackjackTable.Phase.LOBBY) return@synchronized -1L
            val idx = table.seats.indexOfFirst { it.discordId == discordId }
            if (idx < 0) return@synchronized -2L
            val seat = table.seats.removeAt(idx)
            table.lastActivityAt = Instant.now()
            seat.stake
        }
        if (refund == -1L) return MultiLeaveOutcome.NotSeated // raced into PLAYER_TURNS
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

    private sealed interface MidHandOutcome {
        data object NotApplicable : MidHandOutcome
        data object NotSeated : MidHandOutcome
        data object AlreadyLeaving : MidHandOutcome
        data class Queued(val stakeHeld: Long, val isCurrentActor: Boolean) : MidHandOutcome
    }

    /**
     * Begin the next hand on a multi table. Re-debits each seated
     * player's ante from their wallet — seats whose balance can't cover
     * the ante are dropped from the table outright (they don't get to
     * sit out and watch). If too few seats can pay, the table stays in
     * LOBBY and no debits happen, so the survivors aren't holding
     * escrow they can't use.
     */
    @Transactional
    fun startMultiHand(
        hostDiscordId: Long,
        guildId: Long,
        tableId: Long
    ): MultiStartOutcome {
        val table = tableRegistry.get(tableId) ?: return MultiStartOutcome.TableNotFound
        if (table.guildId != guildId) return MultiStartOutcome.TableNotFound
        if (table.mode != BlackjackTable.Mode.MULTI) return MultiStartOutcome.TableNotFound
        if (table.hostDiscordId != hostDiscordId) return MultiStartOutcome.NotHost

        // Phase 1 (under monitor): basic validation + capture seat ids
        // and remember which seats are already escrowed (just-created/
        // joined this hand) vs. need re-debit (survived a prior hand).
        val pre: StartPreflight = tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.LOBBY) return@lockTable StartPreflight.AlreadyInProgress
            if (t.seats.size < Blackjack.MULTI_MIN_SEATS) return@lockTable StartPreflight.NotEnough
            val needsDebit = t.seats.filter { it.stake == 0L }.map { it.discordId }
            val alreadyEscrowed = t.seats.filter { it.stake > 0L }.map { it.discordId }
            StartPreflight.Ok(needsDebit = needsDebit, alreadyEscrowed = alreadyEscrowed)
        } ?: return MultiStartOutcome.TableNotFound

        val ok = when (pre) {
            StartPreflight.AlreadyInProgress -> return MultiStartOutcome.HandAlreadyInProgress
            StartPreflight.NotEnough -> return MultiStartOutcome.NotEnoughPlayers
            is StartPreflight.Ok -> pre
        }

        // Phase 2: lock users in id order and check who can afford.
        // Seats already escrowed (first hand on a fresh create/join) are
        // implicitly able to play — no re-debit needed.
        val ante = table.ante
        val canPay = mutableListOf<Long>()
        val cantPay = mutableListOf<Long>()
        val locked = if (ok.needsDebit.isNotEmpty()) {
            userService.lockUsersInAscendingOrder(ok.needsDebit, guildId)
        } else emptyMap()
        for (id in ok.needsDebit) {
            val user = locked[id]
            val balance = user?.socialCredit ?: 0L
            if (user != null && balance >= ante) canPay.add(id) else cantPay.add(id)
        }
        val totalAble = ok.alreadyEscrowed.size + canPay.size
        if (totalAble < Blackjack.MULTI_MIN_SEATS) {
            // Drop seats that can't afford to play the next hand so they
            // don't permanently block re-starts. They've never had escrow
            // taken from them — nothing to refund.
            tableRegistry.lockTable(tableId) { t ->
                t.seats.removeAll { it.discordId in cantPay }
            }
            return MultiStartOutcome.NotEnoughPlayers
        }

        // Debit each can-pay seat. Already-escrowed seats keep their stake.
        for (id in canPay) {
            val user = locked[id] ?: continue
            user.socialCredit = (user.socialCredit ?: 0L) - ante
            userService.updateUser(user)
        }

        // Phase 3 (under monitor): drop unable seats, set stakes, deal.
        return tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.LOBBY) {
                // Race: another start sneaked in. The phase-2 debit is
                // already committed for this txn — refund and bail.
                for (id in canPay) {
                    val user = locked[id] ?: continue
                    user.socialCredit = (user.socialCredit ?: 0L) + ante
                    userService.updateUser(user)
                }
                return@lockTable MultiStartOutcome.HandAlreadyInProgress
            }
            t.seats.removeAll { it.discordId in cantPay }
            for (seat in t.seats) {
                seat.hand = mutableListOf()
                seat.doubled = false
                seat.pendingLeave = false
                seat.status = BlackjackTable.SeatStatus.ACTIVE
                if (seat.stake == 0L) {
                    seat.stake = ante
                    seat.ante = ante
                }
            }
            t.dealer.clear()
            val deck = blackjack.newDeck()
            t.deck = deck
            // Deal 2 cards each, alternating per seat then dealer (cosmetic).
            repeat(2) {
                for (seat in t.seats) seat.hand.add(deck.deal())
                t.dealer.add(deck.deal())
            }
            for (seat in t.seats) {
                if (isBlackjack(seat.hand)) seat.status = BlackjackTable.SeatStatus.BLACKJACK
            }
            t.handNumber += 1L
            t.phase = BlackjackTable.Phase.PLAYER_TURNS
            t.actorIndex = nextActiveSeatIndex(t, fromInclusive = 0)
            t.lastActivityAt = Instant.now()

            if (t.actorIndex < 0) {
                // Everyone got natural BJ — settle immediately.
                resolveMultiHand(t, guildId)
                tableRegistry.cancelShotClock(tableId)
            } else {
                tableRegistry.rearmShotClock(tableId)
            }
            MultiStartOutcome.Ok(t.handNumber)
        } ?: MultiStartOutcome.TableNotFound
    }

    private sealed interface StartPreflight {
        data class Ok(val needsDebit: List<Long>, val alreadyEscrowed: List<Long>) : StartPreflight
        data object AlreadyInProgress : StartPreflight
        data object NotEnough : StartPreflight
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

            // Cascade past any subsequent pendingLeave seats — auto-stand
            // them on their behalf so the hand never stalls on someone
            // who's already asked to leave the table.
            while (t.phase == BlackjackTable.Phase.PLAYER_TURNS) {
                val nextActor = t.seats.getOrNull(t.actorIndex) ?: break
                if (!nextActor.pendingLeave) break
                if (nextActor.status != BlackjackTable.SeatStatus.ACTIVE) break
                nextActor.status = BlackjackTable.SeatStatus.STANDING
                advanceActor(t)
            }

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
            MultiTransition.Continue -> {
                tableRegistry.rearmShotClock(tableId)
                MultiActionOutcome.Continued(table)
            }
            MultiTransition.Resolve -> {
                val result = tableRegistry.lockTable(tableId) { t ->
                    resolveMultiHand(t, guildId)
                } ?: return MultiActionOutcome.TableNotFound
                tableRegistry.cancelShotClock(tableId)
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
     * Dealer plays out (skipped if every seat busted), then we evaluate
     * every seat against the dealer and split the pot:
     *
     *   - Pushed antes are refunded to the push players.
     *   - Each winner gets their own ante back from the pot.
     *   - Natural-blackjack winners get an extra 1.5× ante "premium" off
     *     the top, capped at the losers' contribution; if the losers'
     *     pool can't cover the full premium it scales down proportionally.
     *   - Whatever remains of the losers' pool after the BJ premium is
     *     split equally among ALL winners (BJ + regular). 5% rake on the
     *     to-be-split chunk goes to the jackpot pool.
     *   - If no seat won, the entire losers' pool goes to the jackpot.
     *
     * After payouts, seats are reset to a fresh per-hand state but
     * KEPT — players re-ante on the next [startMultiHand] without
     * having to re-join the table. Pending-leave seats are dropped here
     * so they're refunded as part of this hand's payout if applicable
     * but don't sit at the table for the next deal.
     */
    private fun resolveMultiHand(t: BlackjackTable, guildId: Long): BlackjackTable.HandResult {
        val deck = t.deck ?: error("missing deck on resolve")
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
        val winnerSeats = t.seats.filter {
            val r = results[it.discordId]
            r == Blackjack.Result.PLAYER_WIN || r == Blackjack.Result.PLAYER_BLACKJACK
        }
        val bjWinners = winnerSeats.filter { results[it.discordId] == Blackjack.Result.PLAYER_BLACKJACK }
        val pushTotal = pushSeats.sumOf { it.stake }
        val winnerStakeTotal = winnerSeats.sumOf { it.stake }
        val losersPool = (totalPot - pushTotal - winnerStakeTotal).coerceAtLeast(0L)

        val payouts = LinkedHashMap<Long, Long>()
        for (seat in pushSeats) payouts[seat.discordId] = seat.stake
        for (seat in winnerSeats) payouts.merge(seat.discordId, seat.stake) { a, b -> a + b }

        // Each winner is entitled to a fixed bonus on top of their stake
        // refund: 1× their stake for a regular win, 1.5× for a natural
        // blackjack. We pay these bonuses out of the losers' pool, after
        // skimming 5% rake. If the pool can't cover the full bonus, we
        // scale every bonus down proportionally so the pool is consumed
        // exactly. Any surplus (small entitled amount, big losers' pool)
        // also routes to the jackpot.
        val regularWinners = winnerSeats.filter {
            results[it.discordId] == Blackjack.Result.PLAYER_WIN
        }
        var rake = 0L
        if (winnerSeats.isEmpty()) {
            // No winners: entire losers' pool routes to the jackpot.
            rake = losersPool
        } else if (losersPool > 0L) {
            val baseRake = (losersPool * Blackjack.MULTI_RAKE).toLong()
            val payable = losersPool - baseRake
            val regularEntitled = regularWinners.sumOf { it.stake }
            val bjEntitled = bjWinners.sumOf { (it.stake * 3L) / 2L }
            val totalEntitled = regularEntitled + bjEntitled

            if (totalEntitled > 0L && payable >= totalEntitled) {
                // Full bonuses; surplus to jackpot.
                for (seat in regularWinners) {
                    payouts.merge(seat.discordId, seat.stake) { a, b -> a + b }
                }
                for (seat in bjWinners) {
                    payouts.merge(seat.discordId, (seat.stake * 3L) / 2L) { a, b -> a + b }
                }
                val surplus = payable - totalEntitled
                rake = baseRake + surplus
            } else if (totalEntitled > 0L) {
                // Scale down: each winner gets `(owed × payable) / totalEntitled`.
                for (seat in regularWinners) {
                    val share = (seat.stake * payable) / totalEntitled
                    payouts.merge(seat.discordId, share) { a, b -> a + b }
                }
                for (seat in bjWinners) {
                    val owed = (seat.stake * 3L) / 2L
                    val share = (owed * payable) / totalEntitled
                    payouts.merge(seat.discordId, share) { a, b -> a + b }
                }
                rake = baseRake
            } else {
                rake = losersPool
            }
        }
        if (rake > 0L) jackpotService.addToPool(guildId, rake)

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

        // Reset to LOBBY but KEEP seats. Each surviving seat needs to re-
        // ante on the next [startMultiHand] — set stake=0 to mark "no
        // escrow held". Drop seats that asked to leave mid-hand.
        t.phase = BlackjackTable.Phase.LOBBY
        t.actorIndex = 0
        t.dealer.clear()
        t.deck = null
        t.currentActorDeadline = null
        t.seats.removeAll { it.pendingLeave }
        for (seat in t.seats) {
            seat.hand = mutableListOf()
            seat.doubled = false
            seat.stake = 0L
            seat.status = BlackjackTable.SeatStatus.ACTIVE
            seat.pendingLeave = false
        }
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
