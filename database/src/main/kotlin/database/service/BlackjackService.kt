package database.service

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.bestTotal
import database.blackjack.canSplit
import database.blackjack.isBlackjack
import database.blackjack.isBust
import database.dto.BlackjackHandLogDto
import database.dto.ConfigDto
import database.persistence.BlackjackHandLogPersistence
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
    /**
     * Optional sell-to-cover wiring (mirrors [PokerService]). When
     * either is null (test path with no Spring context), `autoTopUp`
     * requests degrade gracefully to plain `InsufficientCredits` so
     * existing constructors without these collaborators keep working.
     */
    @Autowired(required = false) private val tradeService: EconomyTradeService? = null,
    @Autowired(required = false) private val marketService: TobyCoinMarketService? = null,
    /**
     * Optional hand-log persistence. When null (test path with no
     * Spring context), settled hands are not logged — service still
     * pays out correctly, the audit row just doesn't appear.
     */
    @Autowired(required = false) private val handLogPersistence: BlackjackHandLogPersistence? = null,
    private val random: Random = Random.Default
) {

    sealed interface SoloDealOutcome {
        /** Hand dealt; player must act. [snapshot] is a live reference — embeds should read it under [BlackjackTableRegistry.lockTable] if precise consistency is needed. */
        data class Dealt(
            val tableId: Long,
            val snapshot: BlackjackTable,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : SoloDealOutcome
        /** Natural blackjack short-circuit (player BJ vs non-BJ dealer, or both BJ → push). */
        data class Resolved(
            val tableId: Long,
            val result: BlackjackTable.HandResult,
            val newBalance: Long,
            val jackpotPayout: Long,
            val lossTribute: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : SoloDealOutcome
        data class InvalidStake(val min: Long, val max: Long) : SoloDealOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : SoloDealOutcome
        /** [autoTopUp] = true but the player's TOBY can't cover the credit shortfall. */
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : SoloDealOutcome
        data object UnknownUser : SoloDealOutcome
    }

    sealed interface SoloActionOutcome {
        data class Continued(val snapshot: BlackjackTable, val newBalance: Long) : SoloActionOutcome
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
        /** SPLIT requested but the player can't cover the second ante. */
        data class InsufficientCreditsForSplit(val needed: Long, val have: Long) : SoloActionOutcome
    }

    sealed interface MultiCreateOutcome {
        data class Ok(
            val tableId: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : MultiCreateOutcome
        data class InvalidAnte(val min: Long, val max: Long) : MultiCreateOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : MultiCreateOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : MultiCreateOutcome
        data object UnknownUser : MultiCreateOutcome
    }

    sealed interface MultiJoinOutcome {
        data class Ok(
            val seatIndex: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : MultiJoinOutcome
        data object TableNotFound : MultiJoinOutcome
        data object TableFull : MultiJoinOutcome
        data object AlreadySeated : MultiJoinOutcome
        data object HandInProgress : MultiJoinOutcome
        data class InsufficientCredits(val stake: Long, val have: Long) : MultiJoinOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : MultiJoinOutcome
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
        data class Continued(val snapshot: BlackjackTable, val newBalance: Long) : MultiActionOutcome
        data class HandResolved(val tableId: Long, val result: BlackjackTable.HandResult) : MultiActionOutcome
        data object TableNotFound : MultiActionOutcome
        data object NotSeated : MultiActionOutcome
        data object NotYourTurn : MultiActionOutcome
        data object NoHandInProgress : MultiActionOutcome
        data object IllegalAction : MultiActionOutcome
        data class InsufficientCreditsForDouble(val needed: Long, val have: Long) : MultiActionOutcome
        /** SPLIT requested but the player can't cover the second ante. */
        data class InsufficientCreditsForSplit(val needed: Long, val have: Long) : MultiActionOutcome
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
    fun dealSolo(
        discordId: Long,
        guildId: Long,
        stake: Long,
        autoTopUp: Boolean = false
    ): SoloDealOutcome {
        val params = readMultiTableParams(guildId)
        val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, params.minAnte, params.maxAnte
        )
        var soldCoins = 0L
        var soldNewPrice: Double? = null
        val resolved = when (check) {
            is BalanceCheck.InvalidStake -> return SoloDealOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> return SoloDealOutcome.UnknownUser
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp) return SoloDealOutcome.InsufficientCredits(check.stake, check.have)
                val user = userService.getUserByIdForUpdate(discordId, guildId)
                    ?: return SoloDealOutcome.UnknownUser
                when (val r = resolveBalanceWithOptionalTopUp(user, guildId, check.have, stake)) {
                    is TopUpResolution.Ok -> {
                        soldCoins = r.soldCoins
                        soldNewPrice = r.newPrice
                        BalanceCheck.Ok(r.user, r.balance)
                    }
                    is TopUpResolution.CreditsShort -> return SoloDealOutcome.InsufficientCredits(stake, r.have)
                    is TopUpResolution.CoinsShort -> return SoloDealOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
                }
            }
            is BalanceCheck.Ok -> check
        }
        // Debit stake into seat escrow.
        resolved.user.socialCredit = resolved.balance - stake
        userService.updateUser(resolved.user)

        // Sweep any resolved solo table left behind by this user's previous
        // hand — the action handler now leaves them in registry so the JS
        // poll can render the result, but they shouldn't accumulate.
        sweepResolvedSoloTablesFor(guildId, discordId)

        val table = tableRegistry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = discordId,
            ante = stake,
            maxSeats = 1,
            rules = params.rules
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
                ResolveDecision.ResolveImmediately(seat)
            } else {
                t.phase = BlackjackTable.Phase.PLAYER_TURNS
                ResolveDecision.AwaitPlayer
            }
        } ?: return SoloDealOutcome.UnknownUser

        return when (initial) {
            ResolveDecision.AwaitPlayer -> SoloDealOutcome.Dealt(
                tableId = table.id,
                snapshot = table,
                newBalance = resolved.user.socialCredit ?: (resolved.balance - stake),
                soldTobyCoins = soldCoins,
                newPrice = soldNewPrice
            )
            is ResolveDecision.ResolveImmediately -> {
                val settlement = settleSolo(table, initial.seat, guildId)
                SoloDealOutcome.Resolved(
                    tableId = table.id,
                    result = settlement.handResult,
                    newBalance = settlement.newBalance,
                    jackpotPayout = settlement.jackpotPayout,
                    lossTribute = settlement.lossTribute,
                    soldTobyCoins = soldCoins,
                    newPrice = soldNewPrice
                )
            }
        }
    }

    private sealed interface ResolveDecision {
        data object AwaitPlayer : ResolveDecision
        data class ResolveImmediately(val seat: BlackjackTable.Seat) : ResolveDecision
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

        // DOUBLE / SPLIT both pre-debit the additional stake from the
        // wallet BEFORE taking the table monitor — keeps the credit lock
        // outside the table lock to match the ordering used elsewhere.
        // The follow-up action lockTable still re-validates seat
        // ownership and phase under the monitor before committing.
        when (action) {
            Blackjack.Action.DOUBLE -> {
                val pre = previewSoloDouble(tableId, discordId) ?: return SoloActionOutcome.HandNotFound
                when (pre) {
                    is StakePreCheck.Illegal -> return SoloActionOutcome.IllegalAction
                    is StakePreCheck.Ok -> {
                        val (user, balance) = lockUserAndBalance(discordId, guildId)
                            ?: return SoloActionOutcome.HandNotFound
                        if (balance < pre.amount) {
                            return SoloActionOutcome.InsufficientCreditsForDouble(pre.amount, balance)
                        }
                        user.socialCredit = balance - pre.amount
                        userService.updateUser(user)
                    }
                }
            }
            Blackjack.Action.SPLIT -> {
                val pre = previewSoloSplit(tableId, discordId) ?: return SoloActionOutcome.HandNotFound
                when (pre) {
                    is StakePreCheck.Illegal -> return SoloActionOutcome.IllegalAction
                    is StakePreCheck.Ok -> {
                        val (user, balance) = lockUserAndBalance(discordId, guildId)
                            ?: return SoloActionOutcome.HandNotFound
                        if (balance < pre.amount) {
                            return SoloActionOutcome.InsufficientCreditsForSplit(pre.amount, balance)
                        }
                        user.socialCredit = balance - pre.amount
                        userService.updateUser(user)
                    }
                }
            }
            else -> Unit
        }

        val transition = tableRegistry.lockTable(tableId) { t ->
            val seat = t.seats.firstOrNull() ?: return@lockTable SoloTransition.HandNotFound
            if (seat.discordId != discordId) return@lockTable SoloTransition.NotYourHand
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable SoloTransition.IllegalAction
            val deck = t.deck ?: return@lockTable SoloTransition.HandNotFound

            applyActionToActiveHand(t, seat, action, deck)
            t.lastActivityAt = Instant.now()
            advanceWithinSeat(seat)

            if (seat.isFinished) {
                runDealerAndResolve(t, seat)
            } else {
                SoloTransition.Continue
            }
        } ?: return SoloActionOutcome.HandNotFound

        return when (transition) {
            SoloTransition.HandNotFound -> SoloActionOutcome.HandNotFound
            SoloTransition.NotYourHand -> SoloActionOutcome.NotYourHand
            SoloTransition.IllegalAction -> SoloActionOutcome.IllegalAction
            SoloTransition.Continue -> SoloActionOutcome.Continued(
                snapshot = table,
                newBalance = userService.getUserById(discordId, guildId)?.socialCredit ?: 0L,
            )
            is SoloTransition.Resolve -> {
                val settlement = settleSolo(table, transition.seat, guildId)
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
     * Apply [action] to the seat's *active* hand-slot (the one
     * currently being played). HIT / STAND / DOUBLE only touch the
     * active slot; SPLIT replaces it with two slots, dealing one new
     * card to each. Caller must have already pre-debited any extra
     * stake (DOUBLE / SPLIT) outside the table monitor.
     */
    private fun applyActionToActiveHand(
        t: BlackjackTable,
        seat: BlackjackTable.Seat,
        action: Blackjack.Action,
        deck: database.card.Deck,
    ) {
        val active = seat.activeHand
        when (action) {
            Blackjack.Action.HIT -> {
                blackjack.hit(active.cards, deck)
                if (isBust(active.cards)) {
                    active.status = BlackjackTable.SeatStatus.BUSTED
                } else if (bestTotal(active.cards) == 21) {
                    active.status = BlackjackTable.SeatStatus.STANDING
                }
            }
            Blackjack.Action.STAND -> {
                active.status = BlackjackTable.SeatStatus.STANDING
            }
            Blackjack.Action.DOUBLE -> {
                active.doubled = true
                active.stake = active.stake * 2
                blackjack.hit(active.cards, deck)
                active.status = if (isBust(active.cards)) BlackjackTable.SeatStatus.BUSTED
                else BlackjackTable.SeatStatus.DOUBLED
            }
            Blackjack.Action.SPLIT -> {
                splitActiveHand(seat, active, deck)
            }
        }
    }

    /**
     * Split [active] into two single-card hands, deal one card to each
     * to bring them back to two cards, mark both as `fromSplit`. Aces
     * additionally auto-stand: classic blackjack rule is a single card
     * per split-ace hand, no further hits or doubles.
     */
    private fun splitActiveHand(
        seat: BlackjackTable.Seat,
        active: BlackjackTable.HandSlot,
        deck: database.card.Deck,
    ) {
        val firstCard = active.cards[0]
        val secondCard = active.cards[1]
        val acesSplit = firstCard.rank == database.card.Rank.ACE
        // Reuse [active] as the first split branch. Wallet was pre-debited
        // by the same amount as the original ante, so total at-risk
        // doubles across the two slots.
        val splitStake = active.stake
        active.cards = mutableListOf(firstCard, deck.deal())
        active.fromSplit = true
        active.status = if (acesSplit) BlackjackTable.SeatStatus.STANDING
        else BlackjackTable.SeatStatus.ACTIVE
        val newSlot = BlackjackTable.HandSlot(
            cards = mutableListOf(secondCard, deck.deal()),
            stake = splitStake,
            doubled = false,
            status = if (acesSplit) BlackjackTable.SeatStatus.STANDING
            else BlackjackTable.SeatStatus.ACTIVE,
            fromSplit = true,
        )
        // Insert right after the active slot so the player plays them in
        // dealt order.
        seat.hands.add(seat.activeHandIndex + 1, newSlot)
    }

    /**
     * After applying an action, walk forward through [seat]'s remaining
     * hand-slots, skipping any already-terminal slots (e.g. split aces
     * that auto-stood on creation, or a slot whose own action just took
     * it to STANDING/BUSTED/DOUBLED). Stops on the next ACTIVE slot, or
     * leaves [Seat.activeHandIndex] past the end if every slot is done.
     */
    private fun advanceWithinSeat(seat: BlackjackTable.Seat) {
        if (seat.activeHand.status == BlackjackTable.SeatStatus.ACTIVE) return
        var idx = seat.activeHandIndex + 1
        while (idx < seat.hands.size && seat.hands[idx].status != BlackjackTable.SeatStatus.ACTIVE) {
            idx++
        }
        seat.activeHandIndex = idx.coerceAtMost(seat.hands.size - 1)
    }

    /**
     * Helper invoked under the table monitor when every hand-slot on
     * the seat is finished: dealer plays out (skipped if every slot
     * busted) and the table phase transitions to RESOLVED. Per-hand
     * evaluation happens in [settleSolo].
     */
    private fun runDealerAndResolve(
        t: BlackjackTable,
        seat: BlackjackTable.Seat,
    ): SoloTransition {
        val deck = t.deck ?: return SoloTransition.HandNotFound
        val allBust = seat.hands.all { it.status == BlackjackTable.SeatStatus.BUSTED }
        t.phase = BlackjackTable.Phase.DEALER_TURN
        if (!allBust) {
            blackjack.playOutDealer(t.dealer, deck, hitsSoft17 = t.rules.dealerHitsSoft17)
        }
        t.phase = BlackjackTable.Phase.RESOLVED
        return SoloTransition.Resolve(seat)
    }

    private sealed interface SoloTransition {
        data object HandNotFound : SoloTransition
        data object NotYourHand : SoloTransition
        data object IllegalAction : SoloTransition
        data object Continue : SoloTransition
        data class Resolve(val seat: BlackjackTable.Seat) : SoloTransition
    }

    private sealed interface StakePreCheck {
        data class Ok(val amount: Long) : StakePreCheck
        data object Illegal : StakePreCheck
    }

    /**
     * Pre-flight DOUBLE under the table monitor: returns the additional
     * stake required ([StakePreCheck.Ok]) when the active hand is
     * exactly 2 cards on a non-doubled active slot, [StakePreCheck.Illegal]
     * when those preconditions fail, or `null` when the table itself
     * has vanished. Wallet check + debit happens outside the lock.
     */
    private fun previewSoloDouble(tableId: Long, discordId: Long): StakePreCheck? =
        tableRegistry.lockTable(tableId) { t ->
            val seat = t.seats.firstOrNull() ?: return@lockTable StakePreCheck.Illegal
            if (seat.discordId != discordId) return@lockTable StakePreCheck.Illegal
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable StakePreCheck.Illegal
            val active = seat.activeHand
            if (active.status != BlackjackTable.SeatStatus.ACTIVE) return@lockTable StakePreCheck.Illegal
            if (active.cards.size != 2 || active.doubled) return@lockTable StakePreCheck.Illegal
            // The DOUBLE wallet debit equals the active hand's current
            // stake (which is `seat.ante` for a non-split hand and
            // `seat.ante` per split branch — split hands carry their
            // own pre-double stake of one ante apiece).
            StakePreCheck.Ok(active.stake)
        }

    /**
     * Pre-flight SPLIT: the active slot must be a 2-card pair, on a
     * non-doubled / non-already-split slot. Re-splitting is allowed up
     * to [Blackjack.MAX_SPLIT_HANDS] total hands per seat. Split aces
     * are valid; the resulting two hands auto-stand on creation.
     */
    private fun previewSoloSplit(tableId: Long, discordId: Long): StakePreCheck? =
        tableRegistry.lockTable(tableId) { t ->
            val seat = t.seats.firstOrNull() ?: return@lockTable StakePreCheck.Illegal
            if (seat.discordId != discordId) return@lockTable StakePreCheck.Illegal
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable StakePreCheck.Illegal
            val active = seat.activeHand
            if (active.status != BlackjackTable.SeatStatus.ACTIVE) return@lockTable StakePreCheck.Illegal
            if (active.doubled) return@lockTable StakePreCheck.Illegal
            if (!canSplit(active.cards)) return@lockTable StakePreCheck.Illegal
            if (seat.hands.size >= Blackjack.MAX_SPLIT_HANDS) return@lockTable StakePreCheck.Illegal
            // Re-splitting aces is disallowed by classic rules.
            if (active.fromSplit && active.cards[0].rank == database.card.Rank.ACE) {
                return@lockTable StakePreCheck.Illegal
            }
            // Each split adds exactly one new hand at the same per-hand
            // ante as the original — wallet must cover one more ante.
            StakePreCheck.Ok(active.stake)
        }

    private fun lockUserAndBalance(discordId: Long, guildId: Long): Pair<database.dto.UserDto, Long>? {
        val user = userService.getUserByIdForUpdate(discordId, guildId) ?: return null
        return user to (user.socialCredit ?: 0L)
    }

    private data class SoloSettlement(
        val handResult: BlackjackTable.HandResult,
        val newBalance: Long,
        val jackpotPayout: Long,
        val lossTribute: Long
    )

    /**
     * Iterate every hand-slot on the seat (one per split branch),
     * evaluate each independently, sum the payouts and route the
     * resulting jackpot rolls / loss tributes. Each hand-slot acts as
     * its own wager — splitting effectively turns one bet into N bets.
     */
    private fun settleSolo(
        table: BlackjackTable,
        seat: BlackjackTable.Seat,
        guildId: Long
    ): SoloSettlement {
        val payoutMult = table.rules.blackjackPayoutMultiplier
        val perHand = mutableListOf<BlackjackTable.PerHandResult>()
        val firstHandResult = LinkedHashMap<Long, Blackjack.Result>()
        var totalPayout = 0L
        for ((idx, slot) in seat.hands.withIndex()) {
            val result = blackjack.evaluate(slot.cards, table.dealer, fromSplit = slot.fromSplit)
            slot.status = terminalStatusFor(result, slot.status)
            val multiplier = blackjack.multiplier(result, payoutMult)
            val handPayout = (slot.stake * multiplier).toLong()
            totalPayout += handPayout
            if (idx == 0) firstHandResult[seat.discordId] = result
            perHand.add(
                BlackjackTable.PerHandResult(
                    discordId = seat.discordId,
                    handIndex = idx,
                    cards = slot.cards.toList(),
                    total = bestTotal(slot.cards),
                    stake = slot.stake,
                    doubled = slot.doubled,
                    fromSplit = slot.fromSplit,
                    result = result,
                    payout = handPayout,
                )
            )
        }

        val user = userService.getUserByIdForUpdate(seat.discordId, guildId)
        var newBalance: Long = user?.socialCredit ?: 0L
        if (user != null && totalPayout > 0L) {
            user.socialCredit = newBalance + totalPayout
            userService.updateUser(user)
            newBalance = user.socialCredit ?: newBalance
        }

        // One jackpot roll per winning hand-slot, one loss tribute per
        // losing hand-slot — split = more wagers, so naturally more
        // chances at both. Pushes are no-ops on both axes.
        var jackpotPayout = 0L
        var lossTribute = 0L
        if (user != null) {
            for (hand in perHand) {
                when (hand.result) {
                    Blackjack.Result.PLAYER_WIN, Blackjack.Result.PLAYER_BLACKJACK -> {
                        val rolled = JackpotHelper.rollOnWin(
                            jackpotService, configService, userService, user, guildId, random
                        )
                        if (rolled > 0L) {
                            jackpotPayout += rolled
                            newBalance = user.socialCredit ?: newBalance
                        }
                    }
                    Blackjack.Result.DEALER_WIN, Blackjack.Result.PLAYER_BUST -> {
                        lossTribute += JackpotHelper.divertOnLoss(
                            jackpotService, configService, guildId, hand.stake
                        )
                    }
                    Blackjack.Result.PUSH -> Unit
                }
            }
        }

        val totalStake = seat.totalStake
        val handResult = BlackjackTable.HandResult(
            handNumber = table.handNumber,
            dealer = table.dealer.toList(),
            dealerTotal = bestTotal(table.dealer),
            seatResults = firstHandResult,
            payouts = if (totalPayout > 0L) mapOf(seat.discordId to totalPayout) else emptyMap(),
            pot = totalStake,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand,
        )
        synchronized(table) {
            table.lastResult = handResult
            table.lastActivityAt = Instant.now()
        }
        persistHandLog(table, handResult)
        return SoloSettlement(handResult, newBalance, jackpotPayout, lossTribute)
    }

    /**
     * Map a [Blackjack.Result] to the matching [BlackjackTable.SeatStatus]
     * for an already-finished hand-slot. Existing terminal flags
     * (DOUBLED, BUSTED) are preserved — only ACTIVE / STANDING get
     * overwritten with the result-derived status.
     */
    private fun terminalStatusFor(
        result: Blackjack.Result,
        current: BlackjackTable.SeatStatus,
    ): BlackjackTable.SeatStatus {
        if (current == BlackjackTable.SeatStatus.DOUBLED ||
            current == BlackjackTable.SeatStatus.BUSTED ||
            current == BlackjackTable.SeatStatus.BLACKJACK
        ) return current
        return when (result) {
            Blackjack.Result.PLAYER_BLACKJACK -> BlackjackTable.SeatStatus.BLACKJACK
            Blackjack.Result.PLAYER_BUST -> BlackjackTable.SeatStatus.BUSTED
            else -> current
        }
    }

    /**
     * Append the resolved hand to `blackjack_hand_log` if persistence
     * is wired. Compact card glyphs match what the embed renders.
     */
    private fun persistHandLog(table: BlackjackTable, result: BlackjackTable.HandResult) {
        val persistence = handLogPersistence ?: return
        val players = result.seatResults.keys.joinToString(",")
        val seatResultsStr = result.seatResults.entries.joinToString(",") { (id, r) -> "$id:${r.name}" }
        val payoutsStr = result.payouts.entries.joinToString(",") { (id, amt) -> "$id:$amt" }
        val dealerStr = result.dealer.joinToString(",") { it.toString() }
        runCatching {
            persistence.insert(
                BlackjackHandLogDto(
                    guildId = table.guildId,
                    tableId = table.id,
                    handNumber = result.handNumber,
                    mode = table.mode.name,
                    players = players,
                    dealer = dealerStr,
                    dealerTotal = result.dealerTotal,
                    seatResults = seatResultsStr,
                    payouts = payoutsStr,
                    pot = result.pot,
                    rake = result.rake,
                    resolvedAt = result.resolvedAt
                )
            )
        }
    }

    /** Drop a SOLO table after the player has had a chance to read the result. */
    fun closeSoloTable(tableId: Long) {
        val table = tableRegistry.get(tableId) ?: return
        if (table.mode != BlackjackTable.Mode.SOLO) return
        if (table.phase == BlackjackTable.Phase.RESOLVED) {
            tableRegistry.remove(tableId)
        }
    }

    /**
     * Drop every RESOLVED SOLO table this user is still seated at in [guildId].
     * The action handler intentionally leaves a resolved table behind so the
     * web JS can poll `/state` once more, render the result, and disable
     * buttons; this method runs on the next deal to clear that residue before
     * a fresh table is registered.
     */
    private fun sweepResolvedSoloTablesFor(guildId: Long, discordId: Long) {
        tableRegistry.listForGuild(guildId)
            .filter { t ->
                t.mode == BlackjackTable.Mode.SOLO &&
                    t.phase == BlackjackTable.Phase.RESOLVED &&
                    t.seats.any { it.discordId == discordId }
            }
            .forEach { tableRegistry.remove(it.id) }
    }

    // -------------------------------------------------------------------------
    // MULTI
    // -------------------------------------------------------------------------

    @Transactional
    fun createMultiTable(
        hostDiscordId: Long,
        guildId: Long,
        ante: Long,
        autoTopUp: Boolean = false
    ): MultiCreateOutcome {
        val params = readMultiTableParams(guildId)
        val check = WagerHelper.checkAndLock(
            userService, hostDiscordId, guildId, ante, params.minAnte, params.maxAnte
        )
        var soldCoins = 0L
        var soldNewPrice: Double? = null
        val resolved = when (check) {
            is BalanceCheck.InvalidStake -> return MultiCreateOutcome.InvalidAnte(check.min, check.max)
            BalanceCheck.UnknownUser -> return MultiCreateOutcome.UnknownUser
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp) return MultiCreateOutcome.InsufficientCredits(check.stake, check.have)
                val user = userService.getUserByIdForUpdate(hostDiscordId, guildId)
                    ?: return MultiCreateOutcome.UnknownUser
                when (val r = resolveBalanceWithOptionalTopUp(user, guildId, check.have, ante)) {
                    is TopUpResolution.Ok -> {
                        soldCoins = r.soldCoins
                        soldNewPrice = r.newPrice
                        BalanceCheck.Ok(r.user, r.balance)
                    }
                    is TopUpResolution.CreditsShort -> return MultiCreateOutcome.InsufficientCredits(ante, r.have)
                    is TopUpResolution.CoinsShort -> return MultiCreateOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
                }
            }
            is BalanceCheck.Ok -> check
        }
        resolved.user.socialCredit = resolved.balance - ante
        userService.updateUser(resolved.user)
        val table = tableRegistry.create(
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = hostDiscordId,
            ante = ante,
            maxSeats = params.maxSeats,
            shotClockSeconds = params.shotClockSeconds,
            rules = params.rules
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
        return MultiCreateOutcome.Ok(
            tableId = table.id,
            soldTobyCoins = soldCoins,
            newPrice = soldNewPrice
        )
    }

    @Transactional
    fun joinMultiTable(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        autoTopUp: Boolean = false
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
        var soldCoins = 0L
        var soldNewPrice: Double? = null
        val payerBalance: Long
        val payerUser: database.dto.UserDto
        if (balance < ante) {
            if (!autoTopUp) return MultiJoinOutcome.InsufficientCredits(ante, balance)
            when (val r = resolveBalanceWithOptionalTopUp(user, guildId, balance, ante)) {
                is TopUpResolution.Ok -> {
                    soldCoins = r.soldCoins
                    soldNewPrice = r.newPrice
                    payerUser = r.user
                    payerBalance = r.balance
                }
                is TopUpResolution.CreditsShort -> return MultiJoinOutcome.InsufficientCredits(ante, r.have)
                is TopUpResolution.CoinsShort -> return MultiJoinOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            }
        } else {
            payerUser = user
            payerBalance = balance
        }

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

        payerUser.socialCredit = payerBalance - ante
        userService.updateUser(payerUser)
        return MultiJoinOutcome.Ok(
            seatIndex = seatIndex,
            newBalance = payerUser.socialCredit ?: 0L,
            soldTobyCoins = soldCoins,
            newPrice = soldNewPrice
        )
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

        // DOUBLE / SPLIT pre-debit happens outside the table monitor.
        when (action) {
            Blackjack.Action.DOUBLE -> {
                val pre = previewMultiDouble(tableId, discordId) ?: return MultiActionOutcome.TableNotFound
                when (pre) {
                    MultiPreflight.NoHand -> return MultiActionOutcome.NoHandInProgress
                    MultiPreflight.NotYourTurn -> return MultiActionOutcome.NotYourTurn
                    MultiPreflight.Illegal -> return MultiActionOutcome.IllegalAction
                    is MultiPreflight.Ok -> {
                        val (user, balance) = lockUserAndBalance(discordId, guildId)
                            ?: return MultiActionOutcome.NotSeated
                        if (balance < pre.amount) {
                            return MultiActionOutcome.InsufficientCreditsForDouble(pre.amount, balance)
                        }
                        user.socialCredit = balance - pre.amount
                        userService.updateUser(user)
                    }
                }
            }
            Blackjack.Action.SPLIT -> {
                val pre = previewMultiSplit(tableId, discordId) ?: return MultiActionOutcome.TableNotFound
                when (pre) {
                    MultiPreflight.NoHand -> return MultiActionOutcome.NoHandInProgress
                    MultiPreflight.NotYourTurn -> return MultiActionOutcome.NotYourTurn
                    MultiPreflight.Illegal -> return MultiActionOutcome.IllegalAction
                    is MultiPreflight.Ok -> {
                        val (user, balance) = lockUserAndBalance(discordId, guildId)
                            ?: return MultiActionOutcome.NotSeated
                        if (balance < pre.amount) {
                            return MultiActionOutcome.InsufficientCreditsForSplit(pre.amount, balance)
                        }
                        user.socialCredit = balance - pre.amount
                        userService.updateUser(user)
                    }
                }
            }
            else -> Unit
        }

        val transition = tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable MultiTransition.NoHand
            val actorSeat = t.seats.getOrNull(t.actorIndex) ?: return@lockTable MultiTransition.NoHand
            if (t.seats.none { it.discordId == discordId }) return@lockTable MultiTransition.NotSeated
            if (actorSeat.discordId != discordId) return@lockTable MultiTransition.NotYourTurn
            val deck = t.deck ?: return@lockTable MultiTransition.NoHand

            applyActionToActiveHand(t, actorSeat, action, deck)
            t.lastActivityAt = Instant.now()
            advanceWithinSeat(actorSeat)
            // Once every hand-slot on the seat is finished, the seat
            // itself is done — advance to the next seat (which may also
            // get its own split flow on a future click).
            if (actorSeat.isFinished) {
                advanceActor(t)
            }

            // Cascade past any subsequent pendingLeave seats — auto-stand
            // them on their behalf so the hand never stalls on someone
            // who's already asked to leave the table.
            while (t.phase == BlackjackTable.Phase.PLAYER_TURNS) {
                val nextActor = t.seats.getOrNull(t.actorIndex) ?: break
                if (!nextActor.pendingLeave) break
                if (nextActor.activeHand.status != BlackjackTable.SeatStatus.ACTIVE) break
                nextActor.activeHand.status = BlackjackTable.SeatStatus.STANDING
                advanceWithinSeat(nextActor)
                if (nextActor.isFinished) advanceActor(t)
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
                MultiActionOutcome.Continued(
                    snapshot = table,
                    newBalance = userService.getUserById(discordId, guildId)?.socialCredit ?: 0L,
                )
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

    /**
     * Outcome of the multi-table DOUBLE / SPLIT pre-flight: tells the
     * outer code whether the wallet debit should proceed and, if so,
     * how much to debit. Wallet locking happens outside the table
     * monitor (matching the ordering the rest of the service uses).
     */
    private sealed interface MultiPreflight {
        data class Ok(val amount: Long) : MultiPreflight
        data object NoHand : MultiPreflight
        data object NotYourTurn : MultiPreflight
        data object Illegal : MultiPreflight
    }

    private fun previewMultiDouble(tableId: Long, discordId: Long): MultiPreflight? =
        tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable MultiPreflight.NoHand
            val actorSeat = t.seats.getOrNull(t.actorIndex) ?: return@lockTable MultiPreflight.NoHand
            if (actorSeat.discordId != discordId) return@lockTable MultiPreflight.NotYourTurn
            val active = actorSeat.activeHand
            if (active.status != BlackjackTable.SeatStatus.ACTIVE) return@lockTable MultiPreflight.Illegal
            if (active.cards.size != 2 || active.doubled) return@lockTable MultiPreflight.Illegal
            MultiPreflight.Ok(active.stake)
        }

    private fun previewMultiSplit(tableId: Long, discordId: Long): MultiPreflight? =
        tableRegistry.lockTable(tableId) { t ->
            if (t.phase != BlackjackTable.Phase.PLAYER_TURNS) return@lockTable MultiPreflight.NoHand
            val actorSeat = t.seats.getOrNull(t.actorIndex) ?: return@lockTable MultiPreflight.NoHand
            if (actorSeat.discordId != discordId) return@lockTable MultiPreflight.NotYourTurn
            val active = actorSeat.activeHand
            if (active.status != BlackjackTable.SeatStatus.ACTIVE) return@lockTable MultiPreflight.Illegal
            if (active.doubled) return@lockTable MultiPreflight.Illegal
            if (!canSplit(active.cards)) return@lockTable MultiPreflight.Illegal
            if (actorSeat.hands.size >= Blackjack.MAX_SPLIT_HANDS) return@lockTable MultiPreflight.Illegal
            if (active.fromSplit && active.cards[0].rank == database.card.Rank.ACE) {
                return@lockTable MultiPreflight.Illegal
            }
            MultiPreflight.Ok(active.stake)
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
        // Flatten to a single (seat, hand-slot, hand-index) list — each
        // split branch is its own wager and is settled independently
        // (loss tribute / jackpot roll / pot share all happen at the
        // hand-slot level rather than the seat level).
        data class Entry(
            val seat: BlackjackTable.Seat,
            val slot: BlackjackTable.HandSlot,
            val handIndex: Int,
        )
        val entries = t.seats.flatMap { seat ->
            seat.hands.mapIndexed { idx, slot -> Entry(seat, slot, idx) }
        }
        val allBust = entries.all { it.slot.status == BlackjackTable.SeatStatus.BUSTED }
        if (!allBust) {
            blackjack.playOutDealer(t.dealer, deck, hitsSoft17 = t.rules.dealerHitsSoft17)
        }
        t.phase = BlackjackTable.Phase.RESOLVED

        // Evaluate each hand-slot, respecting the fromSplit flag so a
        // split-aces 21 doesn't claim the natural-BJ premium.
        val perSlotResult = entries.associateWith { entry ->
            blackjack.evaluate(entry.slot.cards, t.dealer, fromSplit = entry.slot.fromSplit)
        }
        // Apply terminal status to each slot for downstream embed
        // rendering (BUSTED / BLACKJACK markers).
        for ((entry, result) in perSlotResult) {
            entry.slot.status = terminalStatusFor(result, entry.slot.status)
        }

        val totalPot = entries.sumOf { it.slot.stake }
        val pushEntries = entries.filter { perSlotResult[it] == Blackjack.Result.PUSH }
        val winnerEntries = entries.filter {
            val r = perSlotResult[it]
            r == Blackjack.Result.PLAYER_WIN || r == Blackjack.Result.PLAYER_BLACKJACK
        }
        val bjEntries = winnerEntries.filter { perSlotResult[it] == Blackjack.Result.PLAYER_BLACKJACK }
        val regularEntries = winnerEntries.filter { perSlotResult[it] == Blackjack.Result.PLAYER_WIN }
        val pushTotal = pushEntries.sumOf { it.slot.stake }
        val winnerStakeTotal = winnerEntries.sumOf { it.slot.stake }
        val losersPool = (totalPot - pushTotal - winnerStakeTotal).coerceAtLeast(0L)

        // Per-slot payouts → aggregated per-discordId at the end. Pushed
        // slots get their own stake refunded; winners get their own
        // stake refunded plus a share of the losers' pool.
        val payoutBySlot = HashMap<Entry, Long>()
        for (e in pushEntries) payoutBySlot[e] = e.slot.stake
        for (e in winnerEntries) payoutBySlot[e] = e.slot.stake

        val bjPremiumFraction = (t.rules.blackjackPayoutMultiplier - 1.0).coerceAtLeast(0.0)
        var rake = 0L
        if (winnerEntries.isEmpty()) {
            rake = losersPool
        } else if (losersPool > 0L) {
            val baseRake = (losersPool * t.rules.rakeFraction).toLong()
            val payable = losersPool - baseRake
            val regularEntitled = regularEntries.sumOf { it.slot.stake }
            val bjEntitled = bjEntries.sumOf { (it.slot.stake * bjPremiumFraction).toLong() }
            val totalEntitled = regularEntitled + bjEntitled

            if (totalEntitled > 0L && payable >= totalEntitled) {
                for (e in regularEntries) payoutBySlot.merge(e, e.slot.stake) { a, b -> a + b }
                for (e in bjEntries) {
                    payoutBySlot.merge(e, (e.slot.stake * bjPremiumFraction).toLong()) { a, b -> a + b }
                }
                rake = baseRake + (payable - totalEntitled)
            } else if (totalEntitled > 0L) {
                for (e in regularEntries) {
                    val share = (e.slot.stake * payable) / totalEntitled
                    payoutBySlot.merge(e, share) { a, b -> a + b }
                }
                for (e in bjEntries) {
                    val owed = (e.slot.stake * bjPremiumFraction).toLong()
                    val share = (owed * payable) / totalEntitled
                    payoutBySlot.merge(e, share) { a, b -> a + b }
                }
                rake = baseRake
            } else {
                rake = losersPool
            }
        }
        if (rake > 0L) jackpotService.addToPool(guildId, rake)

        // Roll into per-discordId totals + per-slot result records.
        val payouts = LinkedHashMap<Long, Long>()
        for ((entry, amount) in payoutBySlot) {
            if (amount <= 0L) continue
            payouts.merge(entry.seat.discordId, amount) { a, b -> a + b }
        }
        if (payouts.isNotEmpty()) {
            val locked = userService.lockUsersInAscendingOrder(payouts.keys, guildId)
            for ((id, amount) in payouts) {
                val user = locked[id] ?: continue
                user.socialCredit = (user.socialCredit ?: 0L) + amount
                userService.updateUser(user)
            }
        }

        val perHand = entries.map { entry ->
            BlackjackTable.PerHandResult(
                discordId = entry.seat.discordId,
                handIndex = entry.handIndex,
                cards = entry.slot.cards.toList(),
                total = bestTotal(entry.slot.cards),
                stake = entry.slot.stake,
                doubled = entry.slot.doubled,
                fromSplit = entry.slot.fromSplit,
                result = perSlotResult[entry] ?: Blackjack.Result.PUSH,
                payout = payoutBySlot[entry] ?: 0L,
            )
        }
        // For back-compat callers reading the per-discordId map, expose
        // each seat's first-hand result as the representative outcome
        // (richer per-hand data lives on perHandResults).
        val results = LinkedHashMap<Long, Blackjack.Result>()
        for (seat in t.seats) {
            val firstSlotResult = perSlotResult.entries.firstOrNull { it.key.seat === seat }?.value
                ?: Blackjack.Result.PUSH
            results[seat.discordId] = firstSlotResult
        }

        val handResult = BlackjackTable.HandResult(
            handNumber = t.handNumber,
            dealer = t.dealer.toList(),
            dealerTotal = bestTotal(t.dealer),
            seatResults = results,
            payouts = payouts,
            pot = totalPot,
            rake = rake,
            resolvedAt = Instant.now(),
            perHandResults = perHand,
        )
        t.lastResult = handResult
        t.lastActivityAt = Instant.now()
        persistHandLog(t, handResult)

        // Reset to LOBBY but KEEP seats. Each surviving seat needs to
        // re-ante on the next [startMultiHand]; resetForNextHand zeroes
        // out the per-hand state including any split branches.
        t.phase = BlackjackTable.Phase.LOBBY
        t.actorIndex = 0
        t.dealer.clear()
        t.deck = null
        t.currentActorDeadline = null
        t.seats.removeAll { it.pendingLeave }
        for (seat in t.seats) {
            seat.resetForNextHand(stake = 0L)
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

    // -------------------------------------------------------------------------
    // HISTORY
    // -------------------------------------------------------------------------

    /**
     * Most recent settled hands for [tableId] within [guildId], newest
     * first. At most [limit] rows; non-positive limits yield empty.
     * Returns empty when persistence isn't wired (test path).
     */
    fun recentHandsForTable(
        guildId: Long,
        tableId: Long,
        limit: Int = HISTORY_DEFAULT_LIMIT
    ): List<BlackjackHandLogDto> {
        if (limit <= 0) return emptyList()
        val persistence = handLogPersistence ?: return emptyList()
        return persistence.findRecentByTable(guildId, tableId, limit.coerceAtMost(HISTORY_MAX_LIMIT))
    }

    /**
     * Most recent settled hands across the entire guild, newest first.
     * Used when `/blackjack history` is invoked without a table id.
     */
    fun recentHandsForGuild(
        guildId: Long,
        limit: Int = HISTORY_DEFAULT_LIMIT
    ): List<BlackjackHandLogDto> {
        if (limit <= 0) return emptyList()
        val persistence = handLogPersistence ?: return emptyList()
        return persistence.findRecentByGuild(guildId, limit.coerceAtMost(HISTORY_MAX_LIMIT))
    }

    // -------------------------------------------------------------------------
    // CONFIG SNAPSHOT + AUTO-TOPUP
    // -------------------------------------------------------------------------

    /**
     * Snapshot of every per-guild blackjack table parameter. Each value
     * falls back to the [Blackjack.companion] default if unset or
     * unparseable. Read once per table at create time, never live, so a
     * mid-hand admin tweak can't change the rules under an in-flight game.
     */
    data class TableParams(
        val minAnte: Long,
        val maxAnte: Long,
        val maxSeats: Int,
        val shotClockSeconds: Int,
        val rules: BlackjackTable.TableRules,
    )

    fun readMultiTableParams(guildId: Long): TableParams {
        fun cfgLong(key: ConfigDto.Configurations, default: Long, min: Long): Long {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            return raw?.toLongOrNull()?.coerceAtLeast(min) ?: default
        }
        fun cfgInt(key: ConfigDto.Configurations, default: Int, range: IntRange): Int {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            return raw?.toIntOrNull()?.coerceIn(range) ?: default
        }
        fun cfgBool(key: ConfigDto.Configurations, default: Boolean): Boolean {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            return raw?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default
        }
        fun cfgPctFraction(key: ConfigDto.Configurations, default: Double, max: Double): Double {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            val pct = raw?.toDoubleOrNull() ?: return default
            if (pct.isNaN() || pct.isInfinite() || pct < 0.0) return default
            return (pct / 100.0).coerceAtMost(max)
        }
        val minAnte = cfgLong(ConfigDto.Configurations.BLACKJACK_MIN_ANTE, Blackjack.MULTI_MIN_ANTE, 1L)
        val maxAnte = cfgLong(ConfigDto.Configurations.BLACKJACK_MAX_ANTE, Blackjack.MULTI_MAX_ANTE, minAnte)
        val maxSeats = cfgInt(ConfigDto.Configurations.BLACKJACK_MAX_SEATS, Blackjack.MULTI_MAX_SEATS, 2..7)
        val shotClock = cfgInt(
            ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS,
            Blackjack.MULTI_SHOT_CLOCK_SECONDS,
            0..600
        )
        val hitsSoft17 = cfgBool(ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17, default = false)
        val rake = cfgPctFraction(ConfigDto.Configurations.BLACKJACK_RAKE_PCT, default = Blackjack.MULTI_RAKE, max = 0.20)
        // BJ payout num/den → multiplier = 1 + num/den. Fall back to 3:2.
        val numRaw = configService.getConfigByName(
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM.configValue, guildId.toString()
        )?.value?.toIntOrNull()?.coerceAtLeast(1)
        val denRaw = configService.getConfigByName(
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN.configValue, guildId.toString()
        )?.value?.toIntOrNull()?.coerceAtLeast(1)
        val payoutMult: Double = if (numRaw != null && denRaw != null) {
            1.0 + numRaw.toDouble() / denRaw.toDouble()
        } else {
            Blackjack.BLACKJACK_MULT
        }
        return TableParams(
            minAnte = minAnte,
            maxAnte = maxAnte,
            maxSeats = maxSeats,
            shotClockSeconds = shotClock,
            rules = BlackjackTable.TableRules(
                dealerHitsSoft17 = hitsSoft17,
                blackjackPayoutMultiplier = payoutMult,
                rakeFraction = rake,
            )
        )
    }

    /**
     * v2-4 (auto-topup) shared between [dealSolo], [createMultiTable]
     * and [joinMultiTable]. If [currentBalance] already covers [target]
     * this is a no-op (Ok with `soldCoins=0`). Otherwise sells just
     * enough TOBY via [CasinoTopUpHelper.ensureCreditsForWager].
     * Failures map back to the per-method `InsufficientCredits` /
     * `InsufficientCoinsForTopUp` variants.
     */
    private fun resolveBalanceWithOptionalTopUp(
        user: database.dto.UserDto,
        guildId: Long,
        currentBalance: Long,
        target: Long,
    ): TopUpResolution {
        if (currentBalance >= target) {
            return TopUpResolution.Ok(user = user, balance = currentBalance, soldCoins = 0L, newPrice = null)
        }
        val safeTrade = tradeService
        val safeMarket = marketService
        if (safeTrade == null || safeMarket == null) {
            return TopUpResolution.CreditsShort(have = currentBalance, needed = target)
        }
        return when (val r = CasinoTopUpHelper.ensureCreditsForWager(
            safeTrade, safeMarket, userService, user, guildId, currentBalance, target
        )) {
            is TopUpResult.ToppedUp ->
                TopUpResolution.Ok(user = r.user, balance = r.balance, soldCoins = r.soldCoins, newPrice = r.newPrice)
            TopUpResult.MarketUnavailable ->
                TopUpResolution.CreditsShort(have = currentBalance, needed = target)
            is TopUpResult.InsufficientCoins ->
                TopUpResolution.CoinsShort(needed = r.needed, have = r.have)
        }
    }

    private sealed interface TopUpResolution {
        data class Ok(
            val user: database.dto.UserDto,
            val balance: Long,
            val soldCoins: Long,
            val newPrice: Double?,
        ) : TopUpResolution
        data class CreditsShort(val have: Long, val needed: Long) : TopUpResolution
        data class CoinsShort(val needed: Long, val have: Long) : TopUpResolution
    }

    companion object {
        // Default and ceiling for /blackjack history depth. Matched to a
        // single Discord embed that fits in the 4096-char description
        // budget; the cap protects against pathological queries.
        const val HISTORY_DEFAULT_LIMIT: Int = 10
        const val HISTORY_MAX_LIMIT: Int = 25
    }
}
