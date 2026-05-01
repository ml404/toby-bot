package database.service

import database.dto.ConfigDto
import database.dto.PokerHandLogDto
import database.dto.PokerHandPotDto
import database.persistence.PokerHandLogPersistence
import database.persistence.PokerHandPotPersistence
import database.poker.PokerEngine
import database.poker.PokerEngine.PokerAction
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
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
 *   - [cashOut] — only allowed between hands ([PokerTable.Phase.WAITING]) —
 *     credits the seat's remaining chips back into `socialCredit` and
 *     removes the seat. The idle-table sweeper does the same thing
 *     automatically when a table goes quiet for [PokerTableRegistry.DEFAULT_IDLE_TTL].
 *
 * Hand resolution flows back through [applyAction] / [startHand]; on a
 * resolved hand the rake (default 5 %, configurable per guild via
 * [ConfigDto.Configurations.POKER_RAKE_PCT]) is routed to the existing
 * per-guild jackpot pool via [JackpotService.addToPool], matching how
 * `/duel` and the slot machines feed it. A row is appended to
 * `poker_hand_log` for audit/leaderboard purposes.
 *
 * v2 (PR #v2-1) — proper side pots and call-for-less. When players
 * go all-in for different amounts, [PokerTable.HandResult.pots]
 * splits the pot into tiers. A short stack can no longer scoop chips
 * they didn't match. Each tier is persisted as its own row in
 * `poker_hand_pot` for audit; `poker_hand_log.pot` stays the
 * across-tiers aggregate for v1 readers.
 *
 * Remaining v1 simplifications (slated for later v2 PRs):
 *   - Buy-in / cash-out only allowed when the table isn't mid-hand.
 *     If a player wants to leave during a hand, they fold and wait.
 *   - Auto-topup (sell TOBY to make rent like the other casinos do via
 *     [CasinoTopUpHelper]) is available at buy-in time only — the chips
 *     are escrowed once and aren't refilled mid-table.
 */
@Service
class PokerService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val tableRegistry: PokerTableRegistry,
    private val handLogPersistence: PokerHandLogPersistence,
    private val handPotPersistence: PokerHandPotPersistence,
    private val random: Random = Random.Default
) {

    sealed interface CreateOutcome {
        data class Ok(val tableId: Long) : CreateOutcome
        data class InvalidBuyIn(val min: Long, val max: Long) : CreateOutcome
        data class InsufficientCredits(val have: Long, val needed: Long) : CreateOutcome
        data object UnknownUser : CreateOutcome
    }

    sealed interface BuyInOutcome {
        data class Ok(val seatIndex: Int, val newBalance: Long) : BuyInOutcome
        data object TableNotFound : BuyInOutcome
        data object TableFull : BuyInOutcome
        data object AlreadySeated : BuyInOutcome
        data class InvalidBuyIn(val min: Long, val max: Long) : BuyInOutcome
        data class InsufficientCredits(val have: Long, val needed: Long) : BuyInOutcome
        data object UnknownUser : BuyInOutcome
    }

    sealed interface CashOutOutcome {
        data class Ok(val chipsReturned: Long, val newBalance: Long) : CashOutOutcome
        data object TableNotFound : CashOutOutcome
        data object NotSeated : CashOutOutcome
        data object HandInProgress : CashOutOutcome
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

    @PostConstruct
    fun wireRegistry() {
        tableRegistry.setOnIdleEvict { table -> evictAllSeats(table) }
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
        buyIn: Long
    ): CreateOutcome {
        if (buyIn !in MIN_BUY_IN..MAX_BUY_IN) {
            return CreateOutcome.InvalidBuyIn(MIN_BUY_IN, MAX_BUY_IN)
        }
        val user = userService.getUserByIdForUpdate(hostDiscordId, guildId)
            ?: return CreateOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < buyIn) {
            return CreateOutcome.InsufficientCredits(have = balance, needed = buyIn)
        }
        val table = tableRegistry.create(
            guildId = guildId,
            hostDiscordId = hostDiscordId,
            minBuyIn = MIN_BUY_IN,
            maxBuyIn = MAX_BUY_IN,
            smallBlind = SMALL_BLIND,
            bigBlind = BIG_BLIND,
            smallBet = SMALL_BET,
            bigBet = BIG_BET,
            maxRaisesPerStreet = MAX_RAISES_PER_STREET,
            maxSeats = MAX_SEATS
        )
        // Debit and seat under the same lock so we can't end up with an
        // empty table after a credit-debit failure.
        user.socialCredit = balance - buyIn
        userService.updateUser(user)
        synchronized(table) {
            table.seats.add(PokerTable.Seat(discordId = hostDiscordId, chips = buyIn))
        }
        return CreateOutcome.Ok(table.id)
    }

    @Transactional
    fun buyIn(
        discordId: Long,
        guildId: Long,
        tableId: Long,
        buyIn: Long
    ): BuyInOutcome {
        val table = tableRegistry.get(tableId) ?: return BuyInOutcome.TableNotFound
        if (table.guildId != guildId) return BuyInOutcome.TableNotFound
        if (buyIn < table.minBuyIn || buyIn > table.maxBuyIn) {
            return BuyInOutcome.InvalidBuyIn(table.minBuyIn, table.maxBuyIn)
        }

        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BuyInOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < buyIn) {
            return BuyInOutcome.InsufficientCredits(have = balance, needed = buyIn)
        }

        val seatIndex = synchronized(table) {
            if (table.seats.any { it.discordId == discordId }) return@synchronized -1
            if (table.seats.size >= table.maxSeats) return@synchronized -2
            table.seats.add(PokerTable.Seat(discordId = discordId, chips = buyIn))
            table.seats.size - 1
        }
        if (seatIndex == -1) return BuyInOutcome.AlreadySeated
        if (seatIndex == -2) return BuyInOutcome.TableFull

        user.socialCredit = balance - buyIn
        userService.updateUser(user)
        return BuyInOutcome.Ok(seatIndex = seatIndex, newBalance = user.socialCredit ?: 0L)
    }

    @Transactional
    fun cashOut(
        discordId: Long,
        guildId: Long,
        tableId: Long
    ): CashOutOutcome {
        val table = tableRegistry.get(tableId) ?: return CashOutOutcome.TableNotFound
        if (table.guildId != guildId) return CashOutOutcome.TableNotFound

        val chipsToReturn = synchronized(table) {
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

        val newBalance = if (chipsToReturn > 0L) {
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
        return synchronized(table) {
            when (val r = PokerEngine.startHand(table, random, now)) {
                is PokerEngine.StartResult.Started -> StartHandOutcome.Ok(r.handNumber)
                PokerEngine.StartResult.HandAlreadyInProgress -> StartHandOutcome.HandAlreadyInProgress
                PokerEngine.StartResult.NotEnoughPlayers -> StartHandOutcome.NotEnoughPlayers
            }
        }
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
        val outcome = synchronized(table) {
            PokerEngine.applyAction(table, discordId, action, rakeRate, now)
        }
        return when (outcome) {
            is PokerEngine.ApplyResult.Rejected -> ActionOutcome.Rejected(outcome.reason)
            is PokerEngine.ApplyResult.Applied -> when (val ev = outcome.event) {
                is PokerEngine.ActionEvent.Continued -> ActionOutcome.Continued
                is PokerEngine.ActionEvent.StreetAdvanced -> ActionOutcome.StreetAdvanced(ev.newPhase)
                is PokerEngine.ActionEvent.HandResolved -> {
                    persistResult(table, ev.result)
                    if (ev.result.rake > 0L) {
                        jackpotService.addToPool(guildId, ev.result.rake)
                    }
                    ActionOutcome.HandResolved(ev.result)
                }
            }
        }
    }

    /**
     * Read-only snapshot of [tableId] suitable for the web/JSON layer
     * (does not mutate; safe to call without [PokerTableRegistry.lockTable]).
     */
    fun snapshot(tableId: Long): PokerTable? = tableRegistry.get(tableId)

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

    companion object {
        const val MIN_BUY_IN: Long = 100L
        const val MAX_BUY_IN: Long = 5_000L
        const val SMALL_BLIND: Long = 5L
        const val BIG_BLIND: Long = 10L
        const val SMALL_BET: Long = 10L
        const val BIG_BET: Long = 20L
        const val MAX_RAISES_PER_STREET: Int = 4
        const val MAX_SEATS: Int = 6

        const val DEFAULT_RAKE: Double = 0.05
        const val MAX_RAKE: Double = 0.20
    }
}
