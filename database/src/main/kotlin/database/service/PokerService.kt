package database.service

import database.dto.ConfigDto
import database.dto.PokerHandLogDto
import database.dto.PokerHandPotDto
import database.dto.UserDto
import database.persistence.PokerHandLogPersistence
import database.persistence.PokerHandPotPersistence
import database.poker.PokerEngine
import database.poker.PokerEngine.PokerAction
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.random.Random

/**
 * Multiplayer fixed-limit Texas Hold'em over the social-credit economy.
 *
 * Lifecycle of a player's chips:
 *   - [buyIn] — debits `socialCredit` and seats the player at the table
 *     with that many chips in escrow. Locked via
 *     [UserService.getUserByIdForUpdate], identical to the casino
 *     minigame services.
 *   - [PokerEngine] mutates seat chip counts as hands play out.
 *   - [rebuy] (v2-3) — top up an already-seated player's stack between
 *     hands, capped at the table's `maxBuyIn`.
 *   - [cashOut] — credits the seat's remaining chips back into
 *     `socialCredit` and removes the seat. Between hands the cash-out
 *     is immediate; mid-hand (v2-3) the seat is flagged `pendingLeave`,
 *     auto-folded on its turn, and chips return to the wallet as soon
 *     as the hand resolves. The idle-table sweeper does an analogous
 *     refund automatically when a table goes quiet for
 *     [PokerTableRegistry.DEFAULT_IDLE_TTL].
 *
 * Hand resolution flows back through [applyAction] / [startHand]; on a
 * resolved hand the rake (default 5 %, configurable per guild via
 * [ConfigDto.Configurations.POKER_RAKE_PCT]) is routed to the existing
 * per-guild jackpot pool via [JackpotService.addToPool], matching how
 * `/duel` and the slot machines feed it. A row is appended to
 * `poker_hand_log` (and per-tier rows to `poker_hand_pot`) for audit
 * and the [recentHandsForTable] / [recentHandsForGuild] reads behind
 * `/poker history`.
 *
 * v2 PRs delivered so far:
 *   - v2-1: side pots and call-for-less at showdown.
 *   - v2-2: per-guild config (blinds / bets / seat cap / shot clock)
 *     snapshotted at table creation, plus a per-actor shot clock that
 *     auto-folds on timeout via [PokerTableRegistry.rearmShotClock].
 *   - v2-3: `/poker rebuy`, mid-hand `/poker leave`, `/poker history`.
 *
 * Outstanding v1 simplification:
 *   - No auto-topup (sell TOBY to make rent like the other casinos do
 *     via [CasinoTopUpHelper]) on buy-in / rebuy. Players must hold
 *     enough `socialCredit` directly. Slated for v2-4.
 */
@Service
class PokerService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val tableRegistry: PokerTableRegistry,
    private val handLogPersistence: PokerHandLogPersistence,
    private val handPotPersistence: PokerHandPotPersistence,
    /**
     * v2-4: optional sell-to-cover wiring. When null (test-only path
     * with no Spring context), [autoTopUp] requests degrade gracefully
     * to plain [CreateOutcome.InsufficientCredits] /
     * [BuyInOutcome.InsufficientCredits] /
     * [RebuyOutcome.InsufficientCredits] so existing constructors
     * without these collaborators keep working unchanged.
     */
    @Autowired(required = false) private val tradeService: EconomyTradeService? = null,
    @Autowired(required = false) private val marketService: TobyCoinMarketService? = null,
    private val random: Random = Random.Default
) {

    sealed interface CreateOutcome {
        data class Ok(
            val tableId: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : CreateOutcome
        data class InvalidBuyIn(val min: Long, val max: Long) : CreateOutcome
        data class InsufficientCredits(val have: Long, val needed: Long) : CreateOutcome
        /**
         * v2-4: caller passed `autoTopUp=true` but the player's TOBY
         * holdings can't cover the credit shortfall at the live market
         * price. [needed] is the coin count required, [have] is what
         * they hold.
         */
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : CreateOutcome
        data object UnknownUser : CreateOutcome
    }

    sealed interface BuyInOutcome {
        data class Ok(
            val seatIndex: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : BuyInOutcome
        data object TableNotFound : BuyInOutcome
        data object TableFull : BuyInOutcome
        data object AlreadySeated : BuyInOutcome
        data class InvalidBuyIn(val min: Long, val max: Long) : BuyInOutcome
        data class InsufficientCredits(val have: Long, val needed: Long) : BuyInOutcome
        /** v2-4: see [CreateOutcome.InsufficientCoinsForTopUp]. */
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : BuyInOutcome
        data object UnknownUser : BuyInOutcome
    }

    sealed interface CashOutOutcome {
        data class Ok(val chipsReturned: Long, val newBalance: Long) : CashOutOutcome
        data object TableNotFound : CashOutOutcome
        data object NotSeated : CashOutOutcome
        /**
         * Kept around for backward-compat with v1 callers that may still
         * surface a "wait for the hand" message. v2-3 routes mid-hand
         * leaves through [QueuedForEndOfHand] instead, but this remains
         * a valid outcome for any pre-existing test that pinned to it.
         */
        data object HandInProgress : CashOutOutcome
        /**
         * v2 (PR #v2-3): the player asked to leave during a hand. Their
         * seat is marked `pendingLeave`; the engine auto-folds them on
         * their turn, and the chips are returned to their wallet as soon
         * as the hand resolves. [chipsHeld] is the seat's stack at the
         * moment the leave was queued — it's NOT the final cash-out
         * amount (the player can still bleed chips from the auto-fold's
         * forced blinds if they were already past blinds, but in
         * practice fold-on-turn after a leave costs nothing extra).
         */
        data class QueuedForEndOfHand(val chipsHeld: Long) : CashOutOutcome
        /**
         * The player already requested to leave this hand. Idempotent
         * second click yields this rather than re-folding them.
         */
        data object AlreadyLeaving : CashOutOutcome
    }

    sealed interface RebuyOutcome {
        data class Ok(
            val seatChips: Long,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : RebuyOutcome
        data object TableNotFound : RebuyOutcome
        data object NotSeated : RebuyOutcome
        data object HandInProgress : RebuyOutcome
        data class InvalidAmount(val min: Long, val max: Long) : RebuyOutcome
        data class StackCapped(val cap: Long, val current: Long) : RebuyOutcome
        data class InsufficientCredits(val have: Long, val needed: Long) : RebuyOutcome
        /** v2-4: see [CreateOutcome.InsufficientCoinsForTopUp]. */
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : RebuyOutcome
        data object UnknownUser : RebuyOutcome
    }

    sealed interface StartHandOutcome {
        data class Ok(val handNumber: Long) : StartHandOutcome
        data object TableNotFound : StartHandOutcome
        data object NotHost : StartHandOutcome
        data object HandAlreadyInProgress : StartHandOutcome
        data object NotEnoughPlayers : StartHandOutcome
    }

    sealed interface ActionOutcome {
        data object Continued : ActionOutcome
        data class StreetAdvanced(val phase: PokerTable.Phase) : ActionOutcome
        data class HandResolved(val result: PokerTable.HandResult) : ActionOutcome
        data class Rejected(val reason: PokerEngine.RejectReason) : ActionOutcome
        data object TableNotFound : ActionOutcome
    }

    /**
     * Self-injection so registry callbacks (which are invoked from a
     * scheduler thread holding `this`, not the Spring proxy) still
     * route `@Transactional` methods through the proxy. Without this,
     * `applyAction` and `evictAllSeats` would bypass their tx advice
     * when called from the idle-evict / shot-clock paths.
     *
     * Nullable + `@Lazy` so a test that constructs the service
     * directly (no Spring) doesn't hit a `lateinit` NPE; in that case
     * [transactionalSelf] falls back to `this`. Production is always
     * Spring-managed so the field is non-null at first use.
     */
    @Autowired
    @Lazy
    private var self: PokerService? = null

    private val transactionalSelf: PokerService get() = self ?: this

    @PostConstruct
    fun wireRegistry() {
        tableRegistry.setOnIdleEvict { table -> transactionalSelf.evictAllSeats(table) }
        tableRegistry.setOnShotClockExpired { table -> autoFoldOnTimeout(table) }
    }

    /**
     * Invoked by [PokerTableRegistry] when an actor's shot clock fires
     * before they've acted. Folds them on their behalf and pushes the
     * action through the same engine path a manual fold would take, so
     * the rest of the table sees a normal "actor folded" event.
     *
     * Calls `self.applyAction` (not `this.applyAction`) so the
     * `@Transactional` boundary on the action handler is honoured —
     * scheduler-thread callbacks otherwise bypass the proxy.
     */
    private fun autoFoldOnTimeout(table: PokerTable) {
        val timedOut = synchronized(table) {
            if (table.phase == PokerTable.Phase.WAITING) return
            table.seats.getOrNull(table.actorIndex)?.discordId
        } ?: return
        transactionalSelf.applyAction(timedOut, table.guildId, table.id, PokerAction.Fold)
    }

    /**
     * Create a new table and seat [hostDiscordId] with [buyIn] chips
     * (debited from `socialCredit`). Combines a [PokerTableRegistry.create]
     * + [buyIn] in a single user-locked transaction so a half-created
     * table can never exist.
     */
    @Transactional
    fun createTable(
        hostDiscordId: Long,
        guildId: Long,
        buyIn: Long,
        autoTopUp: Boolean = false,
        free: Boolean = false
    ): CreateOutcome {
        // v2 (PR #v2-2): each table snapshots the guild's poker config
        // at creation. Mid-hand admin tweaks don't affect the in-flight
        // table — they take effect on the next [createTable] call.
        val params = readTableParams(guildId)
        if (buyIn !in params.minBuyIn..params.maxBuyIn) {
            return CreateOutcome.InvalidBuyIn(params.minBuyIn, params.maxBuyIn)
        }

        // v2-7: free-play tables short-circuit the wallet entirely.
        // The host's `socialCredit` is never touched, so autoTopUp is
        // silently irrelevant. We still demand a chip allocation in
        // [minBuyIn..maxBuyIn] so the table dynamics stay aligned with
        // the configured stakes — the only difference is that those
        // chips are play money.
        if (free) {
            val table = tableRegistry.create(
                guildId = guildId,
                hostDiscordId = hostDiscordId,
                minBuyIn = params.minBuyIn,
                maxBuyIn = params.maxBuyIn,
                smallBlind = params.smallBlind,
                bigBlind = params.bigBlind,
                smallBet = params.smallBet,
                bigBet = params.bigBet,
                maxRaisesPerStreet = MAX_RAISES_PER_STREET,
                maxSeats = params.maxSeats,
                shotClockSeconds = params.shotClockSeconds,
                isFreePlay = true
            )
            synchronized(table) {
                table.seats.add(PokerTable.Seat(discordId = hostDiscordId, chips = buyIn))
            }
            return CreateOutcome.Ok(tableId = table.id)
        }

        val user = userService.getUserByIdForUpdate(hostDiscordId, guildId)
            ?: return CreateOutcome.UnknownUser
        val initialBalance = user.socialCredit ?: 0L
        val resolved = when (val r = resolveBalanceWithOptionalTopUp(user, guildId, initialBalance, buyIn, autoTopUp)) {
            is TopUpResolution.Ok -> r
            is TopUpResolution.CreditsShort -> return CreateOutcome.InsufficientCredits(have = r.have, needed = r.needed)
            is TopUpResolution.CoinsShort -> return CreateOutcome.InsufficientCoinsForTopUp(needed = r.needed, have = r.have)
        }
        val table = tableRegistry.create(
            guildId = guildId,
            hostDiscordId = hostDiscordId,
            minBuyIn = params.minBuyIn,
            maxBuyIn = params.maxBuyIn,
            smallBlind = params.smallBlind,
            bigBlind = params.bigBlind,
            smallBet = params.smallBet,
            bigBet = params.bigBet,
            maxRaisesPerStreet = MAX_RAISES_PER_STREET,
            maxSeats = params.maxSeats,
            shotClockSeconds = params.shotClockSeconds
        )
        // Debit and seat under the same lock so we can't end up with an
        // empty table after a credit-debit failure. Use the post-topup
        // user / balance reference so the debit reflects whatever the
        // sell delivered.
        resolved.user.socialCredit = resolved.balance - buyIn
        userService.updateUser(resolved.user)
        synchronized(table) {
            table.seats.add(PokerTable.Seat(discordId = hostDiscordId, chips = buyIn))
        }
        return CreateOutcome.Ok(
            tableId = table.id,
            soldTobyCoins = resolved.soldCoins,
            newPrice = resolved.newPrice
        )
    }

    /**
     * Snapshot of every per-guild poker table parameter, populated
     * from [ConfigService] with each value falling back to the
     * `PokerService.companion` default if unset or unparseable.
     */
    data class TableParams(
        val smallBlind: Long,
        val bigBlind: Long,
        val smallBet: Long,
        val bigBet: Long,
        val minBuyIn: Long,
        val maxBuyIn: Long,
        val maxSeats: Int,
        val shotClockSeconds: Int
    )

    fun readTableParams(guildId: Long): TableParams {
        fun cfgLong(key: ConfigDto.Configurations, default: Long, min: Long): Long {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            return raw?.toLongOrNull()?.coerceAtLeast(min) ?: default
        }
        fun cfgInt(key: ConfigDto.Configurations, default: Int, range: IntRange): Int {
            val raw = configService.getConfigByName(key.configValue, guildId.toString())?.value
            return raw?.toIntOrNull()?.coerceIn(range) ?: default
        }
        return TableParams(
            smallBlind = cfgLong(ConfigDto.Configurations.POKER_SMALL_BLIND, SMALL_BLIND, 1L),
            bigBlind = cfgLong(ConfigDto.Configurations.POKER_BIG_BLIND, BIG_BLIND, 1L),
            smallBet = cfgLong(ConfigDto.Configurations.POKER_SMALL_BET, SMALL_BET, 1L),
            bigBet = cfgLong(ConfigDto.Configurations.POKER_BIG_BET, BIG_BET, 1L),
            minBuyIn = cfgLong(ConfigDto.Configurations.POKER_MIN_BUY_IN, MIN_BUY_IN, 1L),
            maxBuyIn = cfgLong(ConfigDto.Configurations.POKER_MAX_BUY_IN, MAX_BUY_IN, 1L),
            maxSeats = cfgInt(ConfigDto.Configurations.POKER_MAX_SEATS, MAX_SEATS, 2..9),
            shotClockSeconds = cfgInt(
                ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS,
                DEFAULT_SHOT_CLOCK_SECONDS,
                0..600
            )
        )
    }

    @Transactional
    fun buyIn(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        buyIn: Long,
        autoTopUp: Boolean = false
    ): BuyInOutcome {
        val table = tableRegistry.get(tableId) ?: return BuyInOutcome.TableNotFound
        if (table.guildId != guildId) return BuyInOutcome.TableNotFound
        if (buyIn < table.minBuyIn || buyIn > table.maxBuyIn) {
            return BuyInOutcome.InvalidBuyIn(table.minBuyIn, table.maxBuyIn)
        }

        // Peek seat availability before touching the wallet so an
        // autoTopUp caller doesn't sell TOBY only to bounce off
        // AlreadySeated / TableFull. The atomic add below re-checks
        // under the same monitor — peek can race with another join,
        // but the worst case is a topped-up caller who keeps their
        // credits without a seat (no chip leak).
        val seatPreflight = synchronized(table) {
            when {
                table.seats.any { it.discordId == discordId } -> -1
                table.seats.size >= table.maxSeats -> -2
                else -> 0
            }
        }
        if (seatPreflight == -1) return BuyInOutcome.AlreadySeated
        if (seatPreflight == -2) return BuyInOutcome.TableFull

        // v2-7: free-play tables — short-circuit the wallet entirely.
        // No user lock, no `socialCredit` debit, autoTopUp is silently
        // irrelevant. The seat-add still re-checks under the monitor.
        if (table.isFreePlay) {
            val seatIndex = synchronized(table) {
                if (table.seats.any { it.discordId == discordId }) return@synchronized -1
                if (table.seats.size >= table.maxSeats) return@synchronized -2
                table.seats.add(PokerTable.Seat(discordId = discordId, chips = buyIn))
                table.seats.size - 1
            }
            if (seatIndex == -1) return BuyInOutcome.AlreadySeated
            if (seatIndex == -2) return BuyInOutcome.TableFull
            // Surface the player's untouched wallet balance for symmetry
            // with the real-money path. Look up by id (no for-update
            // lock — we're not mutating).
            val newBalance = userService.getUserById(discordId, guildId)?.socialCredit ?: 0L
            return BuyInOutcome.Ok(seatIndex = seatIndex, newBalance = newBalance)
        }

        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BuyInOutcome.UnknownUser
        val initialBalance = user.socialCredit ?: 0L
        val resolved = when (val r = resolveBalanceWithOptionalTopUp(user, guildId, initialBalance, buyIn, autoTopUp)) {
            is TopUpResolution.Ok -> r
            is TopUpResolution.CreditsShort -> return BuyInOutcome.InsufficientCredits(have = r.have, needed = r.needed)
            is TopUpResolution.CoinsShort -> return BuyInOutcome.InsufficientCoinsForTopUp(needed = r.needed, have = r.have)
        }

        val seatIndex = synchronized(table) {
            if (table.seats.any { it.discordId == discordId }) return@synchronized -1
            if (table.seats.size >= table.maxSeats) return@synchronized -2
            table.seats.add(PokerTable.Seat(discordId = discordId, chips = buyIn))
            table.seats.size - 1
        }
        if (seatIndex == -1) return BuyInOutcome.AlreadySeated
        if (seatIndex == -2) return BuyInOutcome.TableFull

        resolved.user.socialCredit = resolved.balance - buyIn
        userService.updateUser(resolved.user)
        return BuyInOutcome.Ok(
            seatIndex = seatIndex,
            newBalance = resolved.user.socialCredit ?: 0L,
            soldTobyCoins = resolved.soldCoins,
            newPrice = resolved.newPrice
        )
    }

    /**
     * Top up an already-seated player's stack between hands. Mirrors
     * [buyIn] (debits `socialCredit`, escrows the chips on the seat)
     * but stacks onto an existing seat instead of adding a new one.
     *
     * Constraints:
     *   - Only between hands ([PokerTable.Phase.WAITING]). Mid-hand
     *     rebuys would let a busted player rejoin the same hand.
     *   - Post-rebuy stack capped at [PokerTable.maxBuyIn]. A partial
     *     top-up that would breach the cap is rejected outright; the
     *     caller can re-issue with a smaller [amount]. Silent
     *     truncation would be a footgun for the slash-command UX.
     */
    @Transactional
    fun rebuy(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        amount: Long,
        autoTopUp: Boolean = false
    ): RebuyOutcome {
        val table = tableRegistry.get(tableId) ?: return RebuyOutcome.TableNotFound
        if (table.guildId != guildId) return RebuyOutcome.TableNotFound
        if (amount < table.minBuyIn || amount > table.maxBuyIn) {
            return RebuyOutcome.InvalidAmount(table.minBuyIn, table.maxBuyIn)
        }

        // Pre-flight the table-side conditions before the wallet so an
        // autoTopUp caller doesn't sell TOBY only to bounce off
        // HandInProgress / NotSeated / StackCapped. The atomic chip
        // bump below re-checks under the same monitor.
        val seatPreflight: RebuyOutcome? = synchronized(table) {
            when {
                table.phase != PokerTable.Phase.WAITING -> RebuyOutcome.HandInProgress
                else -> {
                    val seat = table.seats.firstOrNull { it.discordId == discordId }
                    when {
                        seat == null -> RebuyOutcome.NotSeated
                        seat.chips + amount > table.maxBuyIn ->
                            RebuyOutcome.StackCapped(cap = table.maxBuyIn, current = seat.chips)
                        else -> null
                    }
                }
            }
        }
        if (seatPreflight != null) return seatPreflight

        // v2-7: free-play tables — short-circuit the wallet entirely.
        // The atomic chip bump below stays the same; we just skip the
        // `socialCredit` debit and any autoTopUp resolution.
        if (table.isFreePlay) {
            val rebuyResult = synchronized(table) {
                when {
                    table.phase != PokerTable.Phase.WAITING -> RebuyOutcome.HandInProgress
                    else -> {
                        val seat = table.seats.firstOrNull { it.discordId == discordId }
                        when {
                            seat == null -> RebuyOutcome.NotSeated
                            seat.chips + amount > table.maxBuyIn ->
                                RebuyOutcome.StackCapped(cap = table.maxBuyIn, current = seat.chips)
                            else -> {
                                seat.chips += amount
                                RebuyOutcome.Ok(seatChips = seat.chips, newBalance = 0L)
                            }
                        }
                    }
                }
            }
            if (rebuyResult !is RebuyOutcome.Ok) return rebuyResult
            val newBalance = userService.getUserById(discordId, guildId)?.socialCredit ?: 0L
            return RebuyOutcome.Ok(seatChips = rebuyResult.seatChips, newBalance = newBalance)
        }

        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return RebuyOutcome.UnknownUser
        val initialBalance = user.socialCredit ?: 0L
        val resolved = when (val r = resolveBalanceWithOptionalTopUp(user, guildId, initialBalance, amount, autoTopUp)) {
            is TopUpResolution.Ok -> r
            is TopUpResolution.CreditsShort -> return RebuyOutcome.InsufficientCredits(have = r.have, needed = r.needed)
            is TopUpResolution.CoinsShort -> return RebuyOutcome.InsufficientCoinsForTopUp(needed = r.needed, have = r.have)
        }

        val rebuyResult = synchronized(table) {
            when {
                table.phase != PokerTable.Phase.WAITING -> RebuyOutcome.HandInProgress
                else -> {
                    val seat = table.seats.firstOrNull { it.discordId == discordId }
                    when {
                        seat == null -> RebuyOutcome.NotSeated
                        seat.chips + amount > table.maxBuyIn ->
                            RebuyOutcome.StackCapped(cap = table.maxBuyIn, current = seat.chips)
                        else -> {
                            seat.chips += amount
                            RebuyOutcome.Ok(seatChips = seat.chips, newBalance = 0L)
                        }
                    }
                }
            }
        }
        if (rebuyResult !is RebuyOutcome.Ok) return rebuyResult

        resolved.user.socialCredit = resolved.balance - amount
        userService.updateUser(resolved.user)
        return RebuyOutcome.Ok(
            seatChips = rebuyResult.seatChips,
            newBalance = resolved.user.socialCredit ?: 0L,
            soldTobyCoins = resolved.soldCoins,
            newPrice = resolved.newPrice
        )
    }

    @Transactional
    fun cashOut(
        discordId: Long,
        guildId: Long,
        tableId: Long
    ): CashOutOutcome {
        val now = Instant.now()
        val table = tableRegistry.get(tableId) ?: return CashOutOutcome.TableNotFound
        if (table.guildId != guildId) return CashOutOutcome.TableNotFound

        // Mid-hand path: queue the leave instead of removing the seat.
        // Sentinel-encode the seat-side outcome so we can release the
        // monitor before deciding whether to drive an immediate fold
        // (which itself takes the monitor again via [applyAction]).
        val midHand = synchronized(table) {
            if (table.phase == PokerTable.Phase.WAITING) {
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
                            chipsHeld = seat.chips,
                            isCurrentActor = seatIdx == table.actorIndex &&
                                seat.status == PokerTable.SeatStatus.ACTIVE
                        )
                    }
                }
            }
        }
        when (midHand) {
            is MidHandOutcome.NotSeated -> return CashOutOutcome.NotSeated
            is MidHandOutcome.AlreadyLeaving -> return CashOutOutcome.AlreadyLeaving
            is MidHandOutcome.Queued -> {
                // If it's the leaver's turn, drive the fold straight
                // through the proxy so the @Transactional / shot-clock /
                // hand-resolution plumbing in [applyAction] runs the same
                // way it would for any other fold. The fold may resolve
                // the hand outright, in which case the post-resolution
                // sweep cashes us out before this method returns.
                if (midHand.isCurrentActor) {
                    transactionalSelf.applyAction(discordId, guildId, tableId, PokerAction.Fold, now)
                }
                return CashOutOutcome.QueuedForEndOfHand(chipsHeld = midHand.chipsHeld)
            }
            is MidHandOutcome.NotApplicable -> Unit // fall through to between-hands path
        }

        // Between-hands path: existing instant cash-out.
        val chipsToReturn = synchronized(table) {
            // Re-check the phase under the monitor: a [startHand] could
            // have raced us between the mid-hand probe and here.
            if (table.phase != PokerTable.Phase.WAITING) return@synchronized -1L
            val idx = table.seats.indexOfFirst { it.discordId == discordId }
            if (idx < 0) return@synchronized -2L
            val seat = table.seats.removeAt(idx)
            // Adjust dealer index so the next hand starts in a consistent spot.
            if (table.seats.isEmpty()) {
                table.dealerIndex = 0
            } else if (idx <= table.dealerIndex) {
                table.dealerIndex = (table.dealerIndex - 1).coerceAtLeast(0) % table.seats.size
            }
            seat.chips
        }
        if (chipsToReturn == -1L) return CashOutOutcome.HandInProgress
        if (chipsToReturn == -2L) return CashOutOutcome.NotSeated

        // v2-7: free-play tables — chips are play money, never credit
        // the wallet. Surface the player's existing `socialCredit`
        // unchanged so the response shape stays uniform with the
        // real-money path.
        val newBalance = if (table.isFreePlay) {
            userService.getUserById(discordId, guildId)?.socialCredit ?: 0L
        } else if (chipsToReturn > 0L) {
            val user = userService.getUserByIdForUpdate(discordId, guildId)
                ?: return CashOutOutcome.NotSeated
            user.socialCredit = (user.socialCredit ?: 0L) + chipsToReturn
            userService.updateUser(user)
            user.socialCredit ?: 0L
        } else {
            userService.getUserById(discordId, guildId)?.socialCredit ?: 0L
        }

        // Drop empty tables so they don't sit forever in the registry.
        synchronized(table) {
            if (table.seats.isEmpty()) tableRegistry.remove(tableId)
        }
        return CashOutOutcome.Ok(chipsReturned = chipsToReturn, newBalance = newBalance)
    }

    private sealed interface MidHandOutcome {
        data object NotApplicable : MidHandOutcome
        data object NotSeated : MidHandOutcome
        data object AlreadyLeaving : MidHandOutcome
        data class Queued(val chipsHeld: Long, val isCurrentActor: Boolean) : MidHandOutcome
    }

    /**
     * Begin the next hand on a table. Only the table host can start a
     * hand (matches the lobby/host pattern of similar Discord games).
     */
    fun startHand(
        hostDiscordId: Long,
        guildId: Long,
        tableId: Long,
        now: Instant = Instant.now()
    ): StartHandOutcome {
        val table = tableRegistry.get(tableId) ?: return StartHandOutcome.TableNotFound
        if (table.guildId != guildId) return StartHandOutcome.TableNotFound
        if (table.hostDiscordId != hostDiscordId) return StartHandOutcome.NotHost
        val started = synchronized(table) {
            when (val r = PokerEngine.startHand(table, random, now)) {
                is PokerEngine.StartResult.Started -> StartHandOutcome.Ok(r.handNumber)
                PokerEngine.StartResult.HandAlreadyInProgress -> StartHandOutcome.HandAlreadyInProgress
                PokerEngine.StartResult.NotEnoughPlayers -> StartHandOutcome.NotEnoughPlayers
            }
        }
        if (started is StartHandOutcome.Ok) tableRegistry.rearmShotClock(tableId, now)
        return started
    }

    /**
     * Apply a betting action ([PokerAction]) for [discordId] on the
     * current street. Returns the engine's outcome plus, on a resolved
     * hand, persists the audit row and routes the rake into the
     * jackpot pool.
     */
    @Transactional
    fun applyAction(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        action: PokerAction,
        now: Instant = Instant.now()
    ): ActionOutcome {
        val table = tableRegistry.get(tableId) ?: return ActionOutcome.TableNotFound
        if (table.guildId != guildId) return ActionOutcome.TableNotFound

        val rakeRate = rakeRate(guildId)
        // Apply the player's action, then keep folding any subsequent
        // actor whose seat is `pendingLeave` (v2-3 mid-hand /poker leave)
        // so the hand never stalls on someone who's already asked to
        // leave the table. The engine itself stays oblivious to
        // pendingLeave — we just feed it Folds on those seats' behalf.
        val outcome = synchronized(table) {
            var current: PokerEngine.ApplyResult =
                PokerEngine.applyAction(table, discordId, action, rakeRate, now)
            cascadeLoop@ while (current is PokerEngine.ApplyResult.Applied) {
                val ev = (current as PokerEngine.ApplyResult.Applied).event
                if (ev is PokerEngine.ActionEvent.HandResolved) break@cascadeLoop
                val nextSeat = table.seats.getOrNull(table.actorIndex) ?: break@cascadeLoop
                if (!nextSeat.pendingLeave) break@cascadeLoop
                if (nextSeat.status != PokerTable.SeatStatus.ACTIVE) break@cascadeLoop
                current = PokerEngine.applyAction(
                    table, nextSeat.discordId, PokerAction.Fold, rakeRate, now
                )
            }
            current
        }
        // Reset the per-actor clock based on what the engine produced:
        // a continuing or street-advancing event puts a fresh decision
        // on a different (or same) actor; a resolved hand stops the
        // clock entirely until [startHand] arms it again.
        when (outcome) {
            is PokerEngine.ApplyResult.Applied -> when (outcome.event) {
                is PokerEngine.ActionEvent.Continued,
                is PokerEngine.ActionEvent.StreetAdvanced ->
                    tableRegistry.rearmShotClock(tableId, now)
                is PokerEngine.ActionEvent.HandResolved -> tableRegistry.cancelShotClock(tableId)
            }
            is PokerEngine.ApplyResult.Rejected -> Unit
        }
        return when (outcome) {
            is PokerEngine.ApplyResult.Rejected -> ActionOutcome.Rejected(outcome.reason)
            is PokerEngine.ApplyResult.Applied -> when (val ev = outcome.event) {
                is PokerEngine.ActionEvent.Continued -> ActionOutcome.Continued
                is PokerEngine.ActionEvent.StreetAdvanced -> ActionOutcome.StreetAdvanced(ev.newPhase)
                is PokerEngine.ActionEvent.HandResolved -> {
                    // v2-7: free-play hands stay in memory only — no
                    // audit row in poker_hand_log, no jackpot route.
                    // sweepPendingLeaves still runs so leavers get
                    // their seats removed (it short-circuits the
                    // wallet credit on free tables).
                    if (!table.isFreePlay) {
                        persistResult(table, ev.result)
                        if (ev.result.rake > 0L) {
                            jackpotService.addToPool(guildId, ev.result.rake)
                        }
                    }
                    // v2-3: cash out any seats marked pendingLeave during
                    // the hand. Done after rake / log so a side-pot
                    // refund the engine credited to a leaver still gets
                    // returned to their wallet on the same call.
                    sweepPendingLeaves(table)
                    ActionOutcome.HandResolved(ev.result)
                }
            }
        }
    }

    /**
     * Refund chips for every seat whose player asked to leave during
     * the just-resolved hand. Called from [applyAction] right after
     * [PokerEngine] reports `HandResolved`. Mirrors [evictAllSeats] but
     * filtered to the pendingLeave subset and with seat removal from
     * the table once the wallet credit lands.
     */
    private fun sweepPendingLeaves(table: PokerTable) {
        val refunds: Map<Long, Long> = synchronized(table) {
            val targets = table.seats.filter { it.pendingLeave }
            if (targets.isEmpty()) return@synchronized emptyMap()
            val out = targets.associate { it.discordId to it.chips }
            // Remove leavers from the seat list. Adjust the dealer index
            // so the next hand starts in a consistent spot, mirroring
            // the between-hands cashOut.
            for (target in targets) {
                val idx = table.seats.indexOf(target)
                table.seats.removeAt(idx)
                if (table.seats.isEmpty()) {
                    table.dealerIndex = 0
                } else if (idx <= table.dealerIndex) {
                    table.dealerIndex =
                        (table.dealerIndex - 1).coerceAtLeast(0) % table.seats.size
                }
            }
            out
        }
        if (refunds.isEmpty()) return

        // v2-7: free-play tables — chips are play money, never credit
        // the wallet. The seat removal above already happened; just
        // skip the user-lock + balance-update pass.
        if (!table.isFreePlay) {
            // Lock all refund recipients in ascending discord-id order to
            // avoid cycles with concurrent /tip, /duel, /coinflip, etc.
            // transactions touching the same users.
            val locked = userService.lockUsersInAscendingOrder(refunds.keys, table.guildId)
            for ((discordId, amount) in refunds) {
                if (amount <= 0L) continue
                val user = locked[discordId] ?: continue
                user.socialCredit = (user.socialCredit ?: 0L) + amount
                userService.updateUser(user)
            }
        }

        // Drop the table if everyone left mid-hand.
        synchronized(table) {
            if (table.seats.isEmpty()) tableRegistry.remove(table.id)
        }
    }

    /**
     * Read-only snapshot of [tableId] suitable for the web/JSON layer
     * (does not mutate; safe to call without [PokerTableRegistry.lockTable]).
     */
    fun snapshot(tableId: Long): PokerTable? = tableRegistry.get(tableId)

    /**
     * Most recent settled hands for [tableId] within [guildId], newest
     * first. Returns at most [limit] rows; passing a non-positive limit
     * yields an empty list. Used by `/poker history` and the web
     * audit panel.
     */
    fun recentHandsForTable(guildId: Long, tableId: Long, limit: Int = HISTORY_DEFAULT_LIMIT): List<PokerHandLogDto> =
        if (limit <= 0) emptyList()
        else handLogPersistence.findRecentByTable(
            guildId, tableId, limit.coerceAtMost(HISTORY_MAX_LIMIT)
        )

    /**
     * Most recent settled hands across the entire guild, newest first.
     * Used when `/poker history` is invoked without a table id.
     */
    fun recentHandsForGuild(guildId: Long, limit: Int = HISTORY_DEFAULT_LIMIT): List<PokerHandLogDto> =
        if (limit <= 0) emptyList()
        else handLogPersistence.findRecentByGuild(
            guildId, limit.coerceAtMost(HISTORY_MAX_LIMIT)
        )

    /** Pure helper exposed for the embeds layer. */
    fun rakeRate(guildId: Long): Double {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.POKER_RAKE_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toDoubleOrNull() ?: return DEFAULT_RAKE
        return (pct / 100.0).coerceIn(0.0, MAX_RAKE)
    }

    private fun persistResult(table: PokerTable, result: PokerTable.HandResult) {
        val playerIds = table.seats.joinToString(",") { it.discordId.toString() }
        val winnerIds = result.winners.joinToString(",")
        val board = result.board.joinToString(",") { it.toString() }
        val log = handLogPersistence.insert(
            PokerHandLogDto(
                guildId = table.guildId,
                tableId = table.id,
                handNumber = result.handNumber,
                players = playerIds,
                winners = winnerIds,
                pot = result.pot,
                rake = result.rake,
                board = board,
                resolvedAt = result.resolvedAt
            )
        )
        // v2: persist the per-tier breakdown alongside the aggregate
        // log row. Older hands (pre-v2 or single-pot resolutions) just
        // produce one tier row.
        val handLogId = log.id ?: return
        for ((idx, tier) in result.pots.withIndex()) {
            handPotPersistence.insert(
                PokerHandPotDto(
                    handLogId = handLogId,
                    tierIndex = idx,
                    cap = tier.cap,
                    amount = tier.amount,
                    eligible = tier.eligibleDiscordIds.joinToString(",") { it.toString() },
                    winners = tier.winners.joinToString(",") { it.toString() },
                    payouts = tier.payoutByDiscordId.entries
                        .joinToString(",") { (id, amt) -> "$id:$amt" }
                )
            )
        }
    }

    /**
     * Refund every seated player's chip stack to their wallet and drop
     * the table. Used by the registry's idle sweeper and the table-host
     * "force close" path.
     */
    @Transactional
    fun evictAllSeats(table: PokerTable) {
        val refunds: Map<Long, Long> = synchronized(table) {
            // Treat anyone with chips as a refund target. Mid-hand evictions
            // donate uncalled bets to the survivors via the table pot, but
            // for an idle wipe we just pay back what's on each stack.
            val out = table.seats.filter { it.chips > 0L }.associate { it.discordId to it.chips }
            table.seats.clear()
            out
        }
        if (refunds.isEmpty()) return

        // v2-7: free-play tables — seats already cleared above; the
        // chip stacks were play money and never came from a wallet,
        // so there's nothing to refund.
        if (table.isFreePlay) return

        // Lock all refund recipients in ascending discord-id order to avoid
        // cycles with concurrent /tip, /duel, /coinflip, etc. transactions
        // that touch the same users.
        val locked = userService.lockUsersInAscendingOrder(refunds.keys, table.guildId)
        for ((discordId, amount) in refunds) {
            val user = locked[discordId] ?: continue
            user.socialCredit = (user.socialCredit ?: 0L) + amount
            userService.updateUser(user)
        }
    }

    /**
     * v2-4 shared between [createTable], [buyIn] and [rebuy]. If
     * [currentBalance] already covers [target] this is a no-op (Ok
     * with `soldCoins=0`). Otherwise, when [autoTopUp] is on AND the
     * trade/market collaborators are present, sells the smallest
     * TOBY amount that lifts the player above [target] via
     * [CasinoTopUpHelper.ensureCreditsForWager]. Failures map back to
     * the per-method `InsufficientCredits` / `InsufficientCoinsForTopUp`
     * variants — caller decides which sealed type to construct.
     */
    private fun resolveBalanceWithOptionalTopUp(
        user: UserDto,
        guildId: Long,
        currentBalance: Long,
        target: Long,
        autoTopUp: Boolean
    ): TopUpResolution {
        if (currentBalance >= target) {
            return TopUpResolution.Ok(user = user, balance = currentBalance, soldCoins = 0L, newPrice = null)
        }
        // No collaborators (test path), or caller didn't opt-in: surface
        // the regular credit-shortfall outcome unchanged.
        val safeTrade = tradeService
        val safeMarket = marketService
        if (!autoTopUp || safeTrade == null || safeMarket == null) {
            return TopUpResolution.CreditsShort(have = currentBalance, needed = target)
        }
        return when (val r = CasinoTopUpHelper.ensureCreditsForWager(
            safeTrade, safeMarket, userService,
            user, guildId, currentBalance, target
        )) {
            is TopUpResult.ToppedUp ->
                TopUpResolution.Ok(user = r.user, balance = r.balance, soldCoins = r.soldCoins, newPrice = r.newPrice)
            // Market degraded — same surfacing as a plain credit shortfall
            // so the player gets the same retry experience.
            TopUpResult.MarketUnavailable ->
                TopUpResolution.CreditsShort(have = currentBalance, needed = target)
            is TopUpResult.InsufficientCoins ->
                TopUpResolution.CoinsShort(needed = r.needed, have = r.have)
        }
    }

    private sealed interface TopUpResolution {
        data class Ok(
            val user: UserDto,
            val balance: Long,
            val soldCoins: Long,
            val newPrice: Double?
        ) : TopUpResolution
        data class CreditsShort(val have: Long, val needed: Long) : TopUpResolution
        data class CoinsShort(val needed: Long, val have: Long) : TopUpResolution
    }

    companion object {
        const val MIN_BUY_IN: Long = 100L
        const val MAX_BUY_IN: Long = 5_000L
        const val SMALL_BLIND: Long = 5L
        const val BIG_BLIND: Long = 10L
        const val SMALL_BET: Long = 10L
        const val BIG_BET: Long = 20L
        const val MAX_RAISES_PER_STREET: Int = 4
        const val MAX_SEATS: Int = 6
        // 30s shot clock by default; 0 disables auto-fold and the table
        // only ever closes via the 10-minute idle sweeper.
        const val DEFAULT_SHOT_CLOCK_SECONDS: Int = 30

        const val DEFAULT_RAKE: Double = 0.05
        const val MAX_RAKE: Double = 0.20

        // /poker history defaults — matched to a single Discord embed
        // that fits comfortably in the 4096-char description budget.
        // The hard ceiling protects against a caller asking for so many
        // rows that the JPA query gets pathological.
        const val HISTORY_DEFAULT_LIMIT: Int = 10
        const val HISTORY_MAX_LIMIT: Int = 25
    }
}
