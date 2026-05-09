package database.service

import common.casino.CasinoCommonFailure
import database.dto.ConfigDto
import database.dto.UserDto
import database.poker.CasinoHoldem
import database.poker.CasinoHoldemTable
import database.poker.CasinoHoldemTableRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.random.Random

/**
 * Atomic play path for `/casinoholdem` (Casino Hold'em vs the dealer).
 * Mirrors the SOLO half of [BlackjackService]:
 *   - dealSolo debits `stake` into table escrow, deals every card
 *     up-front (player + dealer hole + flop + pending turn + river),
 *     and posts the table in [CasinoHoldemTable.Phase.AWAIT_DECISION].
 *   - applyAction with FOLD forfeits the ante, settles, and resolves
 *     the table.
 *   - applyAction with CALL pre-debits `2 × stake`, reveals turn +
 *     river, runs [CasinoHoldem.resolve], pays out per-leg via the
 *     standard paytable, and routes per-leg jackpot rolls / loss
 *     tributes through [JackpotHelper].
 *
 * The wager is split into two legs — ante and call — each with its
 * own multiplier. Splits feed [JackpotHelper] independently so a hand
 * with a winning ante and a losing call leg correctly produces both
 * a jackpot roll AND a loss tribute, the same way blackjack splits
 * each hand-slot through [BlackjackService.settleSolo].
 */
@Service
class CasinoHoldemService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val tableRegistry: CasinoHoldemTableRegistry,
    private val game: CasinoHoldem = CasinoHoldem(),
    /**
     * Optional sell-to-cover wiring. Mirrors [BlackjackService] —
     * when either is null (test path with no Spring context),
     * `autoTopUp` requests degrade gracefully to plain
     * `InsufficientCredits` so existing constructors without these
     * collaborators keep working.
     */
    @Autowired(required = false) private val tradeService: EconomyTradeService? = null,
    @Autowired(required = false) private val marketService: TobyCoinMarketService? = null,
    private val random: Random = Random.Default,
) {

    sealed interface DealOutcome {
        data class Dealt(
            val tableId: Long,
            val snapshot: CasinoHoldemTable,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : DealOutcome
        data class InvalidStake(override val min: Long, override val max: Long) :
            DealOutcome, CasinoCommonFailure.InvalidStake
        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            DealOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            DealOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data object UnknownUser : DealOutcome, CasinoCommonFailure.UnknownUser
    }

    sealed interface ActionOutcome {
        data class Resolved(
            val tableId: Long,
            val result: CasinoHoldemTable.HandResult,
            val newBalance: Long,
            val jackpotPayout: Long,
            val lossTribute: Long,
        ) : ActionOutcome
        data object HandNotFound : ActionOutcome
        data object NotYourHand : ActionOutcome
        data object IllegalAction : ActionOutcome
        data class InsufficientCreditsForCall(val needed: Long, val have: Long) : ActionOutcome
    }

    /**
     * Self-injection so registry callbacks (which run on the scheduler
     * thread, not through the Spring proxy) still go through the
     * `@Transactional` wrapper. Same pattern as [BlackjackService].
     */
    @Autowired
    @Lazy
    private var self: CasinoHoldemService? = null

    private val transactionalSelf: CasinoHoldemService get() = self ?: this

    @PostConstruct
    fun wireRegistry() {
        tableRegistry.setOnIdleEvict { table -> transactionalSelf.refundOnIdleEvict(table) }
    }

    @Transactional
    fun dealSolo(
        discordId: Long,
        guildId: Long,
        stake: Long,
        autoTopUp: Boolean = false,
    ): DealOutcome {
        val resolved = when (val r = checkLockOrTopUp(discordId, guildId, stake, autoTopUp)) {
            is TopUpResolution.InvalidStake -> return DealOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return DealOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return DealOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return DealOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        // Debit the ante into table escrow up-front.
        resolved.user.socialCredit = resolved.balance - stake
        userService.updateUser(resolved.user)

        // Sweep any RESOLVED tables this user left behind from a previous hand.
        sweepResolvedTablesFor(guildId, discordId)

        val table = tableRegistry.create(
            guildId = guildId,
            playerDiscordId = discordId,
            stake = stake,
        )
        tableRegistry.lockTable(table.id) { t ->
            val deck = game.newDeck()
            t.deck = deck
            val deal = game.dealAll(deck)
            t.playerHole.addAll(deal.playerHole)
            t.dealerHole.addAll(deal.dealerHole)
            t.board.addAll(deal.flop)
            t.pendingTurn = deal.turn
            t.pendingRiver = deal.river
            t.phase = CasinoHoldemTable.Phase.AWAIT_DECISION
            t.lastActivityAt = Instant.now()
        } ?: return DealOutcome.UnknownUser

        return DealOutcome.Dealt(
            tableId = table.id,
            snapshot = table,
            newBalance = resolved.user.socialCredit ?: (resolved.balance - stake),
            soldTobyCoins = resolved.soldCoins,
            newPrice = resolved.newPrice,
        )
    }

    @Transactional
    fun applyAction(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        action: CasinoHoldem.Action,
    ): ActionOutcome {
        val table = tableRegistry.get(tableId) ?: return ActionOutcome.HandNotFound
        if (table.guildId != guildId) return ActionOutcome.HandNotFound
        if (table.playerDiscordId != discordId) return ActionOutcome.NotYourHand
        if (table.phase != CasinoHoldemTable.Phase.AWAIT_DECISION) return ActionOutcome.IllegalAction

        return when (action) {
            CasinoHoldem.Action.FOLD -> handleFold(table, guildId)
            CasinoHoldem.Action.CALL -> handleCall(table, guildId, discordId)
        }
    }

    private fun handleFold(
        table: CasinoHoldemTable,
        guildId: Long,
    ): ActionOutcome {
        val result = tableRegistry.lockTable(table.id) { t ->
            if (t.phase != CasinoHoldemTable.Phase.AWAIT_DECISION) return@lockTable null
            val handResult = CasinoHoldemTable.HandResult(
                playerHole = t.playerHole.toList(),
                dealerHole = t.dealerHole.toList(),
                board = t.board.toList(),
                resolution = null,
                folded = true,
                anteStake = t.stake,
                callStake = 0L,
                antePayout = 0L,
                callPayout = 0L,
                totalPayout = 0L,
                resolvedAt = Instant.now(),
            )
            t.phase = CasinoHoldemTable.Phase.RESOLVED
            t.lastResult = handResult
            t.lastActivityAt = Instant.now()
            handResult
        } ?: return ActionOutcome.IllegalAction

        // The ante was already debited at deal time; nothing further to
        // pay out. Tribute the lost ante to the jackpot pool the same
        // way every other game does on a loss.
        val user = userService.getUserByIdForUpdate(table.playerDiscordId, guildId)
        val newBalance = user?.socialCredit ?: 0L
        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, table.stake)
        return ActionOutcome.Resolved(
            tableId = table.id,
            result = result,
            newBalance = newBalance,
            jackpotPayout = 0L,
            lossTribute = tribute,
        )
    }

    private fun handleCall(
        table: CasinoHoldemTable,
        guildId: Long,
        discordId: Long,
    ): ActionOutcome {
        val callStake = table.stake * CasinoHoldem.CALL_MULTIPLE

        // Pre-debit the call leg outside the table monitor — keeps the
        // wallet lock outside the table lock to match the ordering used
        // by BlackjackService for DOUBLE / SPLIT.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return ActionOutcome.HandNotFound
        val balance = user.socialCredit ?: 0L
        if (balance < callStake) {
            return ActionOutcome.InsufficientCreditsForCall(needed = callStake, have = balance)
        }
        user.socialCredit = balance - callStake
        userService.updateUser(user)

        val resolution = tableRegistry.lockTable(table.id) { t ->
            if (t.phase != CasinoHoldemTable.Phase.AWAIT_DECISION) return@lockTable null
            val turn = t.pendingTurn ?: return@lockTable null
            val river = t.pendingRiver ?: return@lockTable null
            t.board.add(turn)
            t.board.add(river)
            t.pendingTurn = null
            t.pendingRiver = null
            game.resolve(t.playerHole.toList(), t.dealerHole.toList(), t.board.toList())
        }

        if (resolution == null) {
            // Table vanished or somehow re-entered while we held the wallet
            // lock — refund the call we just debited and bail.
            user.socialCredit = (user.socialCredit ?: 0L) + callStake
            userService.updateUser(user)
            return ActionOutcome.IllegalAction
        }

        val antePayout = (table.stake * game.anteMultiplier(resolution.anteResult)).toLong()
        val callPayout = (callStake * game.callMultiplier(resolution.callResult)).toLong()
        val totalPayout = antePayout + callPayout

        // Single wallet write to credit the combined payout.
        if (totalPayout > 0L) {
            user.socialCredit = (user.socialCredit ?: 0L) + totalPayout
            userService.updateUser(user)
        }

        // Per-leg jackpot rolls / tributes. WIN legs roll once each (so
        // a hand can produce up to two rolls); LOSE legs feed the pool
        // their own at-risk stake; PUSH legs are no-ops on both axes.
        var jackpotPayout = 0L
        var lossTribute = 0L

        when (resolution.anteResult) {
            CasinoHoldem.AnteResult.WIN ->
                jackpotPayout += JackpotHelper.rollOnWin(
                    jackpotService, configService, userService, user, guildId,
                    table.stake, JackpotGame.HOLDEM, random,
                )
            CasinoHoldem.AnteResult.LOSE ->
                lossTribute += JackpotHelper.divertOnLoss(
                    jackpotService, configService, guildId, table.stake
                )
            CasinoHoldem.AnteResult.PUSH -> Unit
        }

        when (resolution.callResult) {
            CasinoHoldem.CallResult.WIN_ROYAL_FLUSH,
            CasinoHoldem.CallResult.WIN_STRAIGHT_FLUSH,
            CasinoHoldem.CallResult.WIN_QUADS,
            CasinoHoldem.CallResult.WIN_FULL_HOUSE,
            CasinoHoldem.CallResult.WIN_FLUSH,
            CasinoHoldem.CallResult.WIN_STRAIGHT,
            CasinoHoldem.CallResult.WIN_OTHER ->
                jackpotPayout += JackpotHelper.rollOnWin(
                    jackpotService, configService, userService, user, guildId,
                    callStake, JackpotGame.HOLDEM, random,
                )
            CasinoHoldem.CallResult.LOSE ->
                lossTribute += JackpotHelper.divertOnLoss(
                    jackpotService, configService, guildId, callStake
                )
            CasinoHoldem.CallResult.PUSH, CasinoHoldem.CallResult.FOLDED -> Unit
        }

        val newBalance = user.socialCredit ?: 0L

        val handResult = CasinoHoldemTable.HandResult(
            playerHole = table.playerHole.toList(),
            dealerHole = table.dealerHole.toList(),
            board = table.board.toList(),
            resolution = resolution,
            folded = false,
            anteStake = table.stake,
            callStake = callStake,
            antePayout = antePayout,
            callPayout = callPayout,
            totalPayout = totalPayout,
            resolvedAt = Instant.now(),
        )
        // Persist the result on the table so the web poll can render
        // the resolved state until the next deal sweeps it.
        synchronized(table) {
            table.phase = CasinoHoldemTable.Phase.RESOLVED
            table.lastResult = handResult
            table.lastActivityAt = Instant.now()
        }

        return ActionOutcome.Resolved(
            tableId = table.id,
            result = handResult,
            newBalance = newBalance,
            jackpotPayout = jackpotPayout,
            lossTribute = lossTribute,
        )
    }

    /** Drop a RESOLVED Casino Hold'em table after the player has read the result. */
    fun closeSoloTable(tableId: Long) {
        val table = tableRegistry.get(tableId) ?: return
        if (table.phase == CasinoHoldemTable.Phase.RESOLVED) {
            tableRegistry.remove(tableId)
        }
    }

    /**
     * Drop every RESOLVED table this user is still attached to in
     * [guildId]. The action handler intentionally leaves a resolved
     * table behind so the web JS can poll `/state` once more, render
     * the result, and disable buttons; this method runs on the next
     * deal to clear that residue before a fresh table is registered.
     */
    private fun sweepResolvedTablesFor(guildId: Long, discordId: Long) {
        tableRegistry.listForGuild(guildId)
            .filter { t ->
                t.playerDiscordId == discordId &&
                    t.phase == CasinoHoldemTable.Phase.RESOLVED
            }
            .forEach { tableRegistry.remove(it.id) }
    }

    /**
     * Idle-eviction callback wired in [wireRegistry]. Refunds the
     * ante on tables that never reached resolution (player walked
     * away after dealing); RESOLVED tables have already been settled,
     * so this is a no-op for them.
     */
    @Transactional
    fun refundOnIdleEvict(table: CasinoHoldemTable) {
        if (table.phase == CasinoHoldemTable.Phase.RESOLVED) return
        val user = userService.getUserByIdForUpdate(table.playerDiscordId, table.guildId) ?: return
        user.socialCredit = (user.socialCredit ?: 0L) + table.stake
        userService.updateUser(user)
    }

    // -------------------------------------------------------------------------
    // Private helpers — autoTopUp glue copied from BlackjackService's pattern.
    // -------------------------------------------------------------------------

    private sealed interface TopUpResolution {
        data class Ok(
            val user: UserDto,
            val balance: Long,
            val soldCoins: Long,
            val newPrice: Double?,
        ) : TopUpResolution
        data class InvalidStake(val min: Long, val max: Long) : TopUpResolution
        data class StillInsufficientCredits(val stake: Long, val have: Long) : TopUpResolution
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : TopUpResolution
        data object UnknownUser : TopUpResolution
    }

    private fun checkLockOrTopUp(
        discordId: Long,
        guildId: Long,
        stake: Long,
        autoTopUp: Boolean,
    ): TopUpResolution {
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.HOLDEM_MIN_STAKE, guildId, default = CasinoHoldem.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.HOLDEM_MAX_STAKE, guildId, default = CasinoHoldem.MAX_STAKE, min = minStake
        )
        val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake,
            minStake, maxStake
        )
        return when (check) {
            is BalanceCheck.InvalidStake -> TopUpResolution.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> TopUpResolution.UnknownUser
            is BalanceCheck.Ok ->
                TopUpResolution.Ok(check.user, check.balance, soldCoins = 0L, newPrice = null)
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp || tradeService == null || marketService == null) {
                    TopUpResolution.StillInsufficientCredits(check.stake, check.have)
                } else {
                    val user = userService.getUserByIdForUpdate(discordId, guildId)
                        ?: return TopUpResolution.UnknownUser
                    when (val topUp = CasinoTopUpHelper.ensureCreditsForWager(
                        tradeService, marketService, userService,
                        user, guildId, currentBalance = check.have, stake = stake
                    )) {
                        is TopUpResult.InsufficientCoins ->
                            TopUpResolution.InsufficientCoinsForTopUp(topUp.needed, topUp.have)
                        TopUpResult.MarketUnavailable ->
                            TopUpResolution.StillInsufficientCredits(check.stake, check.have)
                        is TopUpResult.ToppedUp ->
                            TopUpResolution.Ok(topUp.user, topUp.balance, topUp.soldCoins, topUp.newPrice)
                    }
                }
            }
        }
    }
}
