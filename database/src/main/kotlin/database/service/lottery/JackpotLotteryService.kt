package database.service.lottery

import common.events.lottery.LotteryWonEvent
import database.dto.lottery.JackpotLotteryDto
import database.dto.lottery.JackpotLotteryTicketDto
import database.dto.lottery.LotteryStreakDto
import database.persistence.lottery.JackpotLotteryPersistence
import database.persistence.lottery.LotteryStreakPersistence
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.random.Random
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.economy.JackpotService
import database.service.lottery.LotteryHelper
import database.service.user.UserService

/**
 * Per-guild jackpot-pool lottery events. Two flavours coexist on the
 * same DB table, distinguished by the row's `mode`:
 *
 *   - **TICKET_WEIGHTED** (admin-fired one-shot drain).
 *     [openLottery] / [buyTickets] / [drawLottery] / [cancelLottery].
 *     Each ticket is a weight; top-K winners share the pool 50/30/20-style.
 *
 *   - **NUMBER_MATCH** (daily auto-drain — Pick N of 1-M, default
 *     5 of 49). [openMatchLottery] / [buyMatchTicket] / [drawMatchLottery].
 *     Players pick N distinct numbers from 1-M; the draw produces N
 *     winning numbers; payouts tier by match count (N down to 2, per
 *     [LotteryHelper.matchTierPcts] — 60/25/10/5 % for the 5-pick
 *     default). 0 / 1 matches pay nothing — those tickets are the
 *     credit sink.
 *
 * V28 added a partial unique index on (guild_id, mode) where status =
 * 'OPEN'; both modes can be open simultaneously without clashing.
 *
 * Concurrency: every mutation runs inside a `@Transactional` boundary
 * with a pessimistic write lock on the lottery row. Per-user ticket
 * rows are locked individually so two simultaneous /lottery buys from
 * the same user can't double-spend.
 */
@Service
@Transactional
class JackpotLotteryService(
    private val lotteryPersistence: JackpotLotteryPersistence,
    private val jackpotService: JackpotService,
    private val userService: UserService,
    private val configService: ConfigService,
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
    /**
     * Nullable so pre-existing direct constructions (tests) keep
     * working; Spring injects the repository bean. When null, streak
     * tracking is a silent no-op.
     */
    private val streakPersistence: LotteryStreakPersistence? = null,
    private val clock: Clock = Clock.systemUTC(),
) {

    // ===================================================================
    // TICKET_WEIGHTED outcomes (existing)
    // ===================================================================

    sealed interface OpenOutcome {
        /**
         * [rolledIn] is the rollover pot claimed from the previous
         * closed lottery of the same (guild, mode) — already included
         * in the new row's pool. 0 when nothing was parked.
         */
        data class Ok(
            val lottery: JackpotLotteryDto,
            val seeded: Long,
            val rolledIn: Long = 0L,
        ) : OpenOutcome
        data object AlreadyOpen : OpenOutcome
        data class InvalidParams(val reason: String) : OpenOutcome
        data object EmptyPool : OpenOutcome
    }

    sealed interface BuyOutcome {
        data class Ok(
            val ticketCount: Int,
            val totalSpent: Long,
            val newBalance: Long,
            val newPool: Long,
            /** Free tickets awarded for this single purchase (bulk-buy bonus). */
            val bonusTicketsGranted: Long = 0L,
            /** User's cumulative bonus tickets on this lottery after the purchase. */
            val totalBonusTickets: Long = 0L,
            /** Pool-growth milestones that fired during this purchase. */
            val milestoneBonuses: List<MilestoneBonus> = emptyList(),
            /** Consecutive participation days including today (0 = untracked). */
            val streakDays: Int = 0,
            /** Streak bonus credits paid on this purchase (0 = none). */
            val streakBonusAwarded: Long = 0L,
        ) : BuyOutcome
        data object NoOpenLottery : BuyOutcome
        data class InvalidCount(val ticketCount: Int) : BuyOutcome
        data class Insufficient(val have: Long, val need: Long) : BuyOutcome
        data object UnknownUser : BuyOutcome
    }

    /**
     * One pool-growth milestone that fired during a [buyTickets] call.
     * [threshold] is the ticket-count it represents (mirrors the config
     * row); [creditsAdded] is what landed in the lottery pool (after
     * clamping to the jackpot's available balance — a near-empty
     * jackpot can deliver less than the configured %).
     */
    data class MilestoneBonus(val threshold: Long, val creditsAdded: Long)

    sealed interface DrawOutcome {
        data class Ok(
            val payouts: List<WinnerPayout>,
            val totalPaid: Long,
            val drained: Long,
            /** Sum of bulk-buy bonus tickets across every buyer at draw time. */
            val bonusTicketsAwarded: Long = 0L,
            /** Highest pool-growth milestone that fired during this lottery's
             *  buy phase (0 = none). Mirrors `JackpotLotteryDto.milestonesFired`. */
            val highestMilestoneFired: Long = 0L,
        ) : DrawOutcome
        data object NoOpenLottery : DrawOutcome
        data object NoTickets : DrawOutcome

        /**
         * Distinct buyer count is below the per-guild
         * `LOTTERY_DAILY_MIN_BUYERS` threshold. The caller should treat
         * this the same as [NoTickets] — call [cancelLottery] to refund
         * all buyers and return the seed to the jackpot pool. Distinct
         * variant from [NoTickets] so the announcer can render the
         * "1 buyer (need 2)" copy instead of "no tickets bought".
         */
        data class BelowMinBuyers(val have: Int, val need: Int) : DrawOutcome
    }

    sealed interface CancelOutcome {
        /**
         * Exactly one of [returnedToPool] / [rolledOver] is non-zero
         * (or both zero): the undistributed seed either went back to
         * the jackpot (default) or was parked as the rollover pot for
         * the next open of the same mode.
         */
        data class Ok(
            val refundedUsers: Int,
            val refundedTotal: Long,
            val returnedToPool: Long,
            val rolledOver: Long = 0L,
        ) : CancelOutcome
        data object NoOpenLottery : CancelOutcome
    }

    data class WinnerPayout(val discordId: Long, val ticketCount: Int, val amount: Long)

    // ===================================================================
    // NUMBER_MATCH outcomes (new)
    // ===================================================================

    sealed interface BuyMatchOutcome {
        data class Ok(
            val pickedNumbers: List<Int>,
            val totalSpent: Long,
            val newBalance: Long,
            val newPool: Long,
            val jackpotInflow: Long,
            /** Consecutive participation days including today (0 = untracked). */
            val streakDays: Int = 0,
            /** Streak bonus credits paid on this purchase (0 = none). */
            val streakBonusAwarded: Long = 0L,
        ) : BuyMatchOutcome
        data object NoOpenLottery : BuyMatchOutcome
        data class InvalidPicks(val reason: String) : BuyMatchOutcome
        data class Insufficient(val have: Long, val need: Long) : BuyMatchOutcome
        data object UnknownUser : BuyMatchOutcome
        data object AlreadyBought : BuyMatchOutcome
    }

    sealed interface DrawMatchOutcome {
        data class Ok(
            val drawnNumbers: List<Int>,
            val tierPayouts: List<MatchTierPayout>,
            val totalPaid: Long,
            val drained: Long,
            val rolledBackToJackpot: Long,
            /** Unpaid remainder parked for tomorrow's draw instead of the jackpot. */
            val rolledOver: Long = 0L,
        ) : DrawMatchOutcome
        data object NoOpenLottery : DrawMatchOutcome
        data object NoTickets : DrawMatchOutcome

        /** Same semantics as [DrawOutcome.BelowMinBuyers]. */
        data class BelowMinBuyers(val have: Int, val need: Int) : DrawMatchOutcome
    }

    /**
     * One winner's match-tier payout.
     * - [matches] is how many of their 5 picks matched the draw
     * - [share] is the *per-winner* credit amount they received
     */
    data class MatchTierPayout(
        val discordId: Long,
        val matches: Int,
        val share: Long,
    )

    // ===================================================================
    // TICKET_WEIGHTED methods (existing)
    // ===================================================================

    fun openLottery(
        guildId: Long,
        ticketPrice: Long,
        durationHours: Long,
        winnerCount: Int,
        drainPct: Double,
    ): OpenOutcome {
        if (ticketPrice <= 0L) return OpenOutcome.InvalidParams("ticket price must be > 0")
        if (durationHours <= 0L) return OpenOutcome.InvalidParams("duration must be > 0 hours")
        if (winnerCount < 1) return OpenOutcome.InvalidParams("winner count must be >= 1")
        if (drainPct <= 0.0 || drainPct > 1.0) return OpenOutcome.InvalidParams("drain pct must be in (0, 1]")

        if (lotteryPersistence.getOpenByGuildAndModeForUpdate(
                guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED
            ) != null
        ) return OpenOutcome.AlreadyOpen

        val poolBefore = jackpotService.getPool(guildId)
        // A parked rollover pot counts as funding: a guild whose jackpot
        // ran dry can still open when yesterday's pot rolled over.
        val pendingRollover = peekRollover(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)
        if (poolBefore == 0L && pendingRollover <= 0L) return OpenOutcome.EmptyPool
        // Preserve the pre-rollover "always seed at least 1" floor when
        // the jackpot has anything in it; a rollover-only open seeds 0.
        val seed = kotlin.math.floor(poolBefore * drainPct).toLong()
            .coerceAtMost(poolBefore)
            .coerceAtLeast(if (poolBefore > 0L) 1L else 0L)

        val drained = drainFromPool(guildId, seed)
        val rolledIn = claimRollover(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)

        val now = Instant.now()
        val lottery = JackpotLotteryDto(
            guildId = guildId,
            ticketPrice = ticketPrice,
            poolAmount = drained + rolledIn,
            winnerCount = winnerCount,
            openedAt = now,
            closesAt = now.plusSeconds(durationHours * 3600L),
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        return OpenOutcome.Ok(lotteryPersistence.upsert(lottery), seeded = drained, rolledIn = rolledIn)
    }

    fun buyTickets(guildId: Long, discordId: Long, ticketCount: Int): BuyOutcome {
        if (ticketCount <= 0) return BuyOutcome.InvalidCount(ticketCount)
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED
        ) ?: return BuyOutcome.NoOpenLottery

        val cost = lottery.ticketPrice * ticketCount.toLong()

        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BuyOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < cost) return BuyOutcome.Insufficient(have = balance, need = cost)

        user.socialCredit = balance - cost
        userService.updateUser(user)

        val lotteryId = lottery.id ?: error("Open lottery has no id")

        // Bulk-buy bonus is computed off *this* purchase's count, so
        // splitting the same N across multiple smaller buys earns
        // nothing — that's the whole point of the incentive.
        val bulkBonus = LotteryHelper.bulkBonusFor(
            ticketCount.toLong(),
            LotteryHelper.bulkBonusTiers(configService, guildId),
        )

        val existing = lotteryPersistence.getTicketForUpdate(lotteryId, discordId)
        val updatedTicket = if (existing == null) {
            JackpotLotteryTicketDto(
                lotteryId = lotteryId,
                discordId = discordId,
                ticketCount = ticketCount,
                spent = cost,
                bonusTickets = bulkBonus,
            )
        } else {
            existing.ticketCount += ticketCount
            existing.spent += cost
            existing.bonusTickets += bulkBonus
            existing
        }
        lotteryPersistence.upsertTicket(updatedTicket)

        lottery.poolAmount += cost

        // Pool-growth milestones. The running guild-wide ticket count
        // is the sum of (paid) ticketCount on every row of this lottery
        // — bonus tickets don't count toward the FOMO threshold,
        // they're a reward for crossing it. Skip the extra query when
        // nothing is configured so a guild that doesn't use milestones
        // pays no perf cost.
        val firedBonuses = mutableListOf<MilestoneBonus>()
        val milestones = LotteryHelper.poolMilestones(configService, guildId)
        if (milestones.isNotEmpty()) {
            val newTotalTickets = lotteryPersistence.ticketsByLottery(lotteryId)
                .sumOf { it.ticketCount.toLong() }
            val prevTotalTickets = newTotalTickets - ticketCount.toLong()
            val milestonesToFire = LotteryHelper.milestonesBetween(
                prevTotal = prevTotalTickets,
                newTotal = newTotalTickets,
                milestones = milestones,
                alreadyFiredHighest = lottery.milestonesFired,
            )
            for ((threshold, pct) in milestonesToFire) {
                val jackpotBefore = jackpotService.getPool(guildId)
                if (jackpotBefore <= 0L) break  // jackpot drained; remaining milestones get nothing
                val take = kotlin.math.floor(jackpotBefore * (pct.toDouble() / 100.0))
                    .toLong()
                    .coerceAtMost(jackpotBefore)
                    .coerceAtLeast(0L)
                if (take <= 0L) continue
                val drained = drainFromPool(guildId, take)
                if (drained <= 0L) continue
                lottery.poolAmount += drained
                lottery.milestonesFired = threshold
                firedBonuses += MilestoneBonus(threshold = threshold, creditsAdded = drained)
            }
        }

        lotteryPersistence.upsert(lottery)

        val streak = recordParticipationAndAward(guildId, discordId, user)

        return BuyOutcome.Ok(
            ticketCount = updatedTicket.ticketCount,
            totalSpent = updatedTicket.spent,
            newBalance = user.socialCredit ?: (balance - cost),
            newPool = lottery.poolAmount,
            bonusTicketsGranted = bulkBonus,
            totalBonusTickets = updatedTicket.bonusTickets,
            milestoneBonuses = firedBonuses,
            streakDays = streak.days,
            streakBonusAwarded = streak.bonusAwarded,
        )
    }

    fun drawLottery(guildId: Long): DrawOutcome {
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED
        ) ?: return DrawOutcome.NoOpenLottery
        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val tickets = lotteryPersistence.ticketsByLottery(lotteryId)
        if (tickets.isEmpty() || tickets.sumOf { it.ticketCount.toLong() } == 0L) return DrawOutcome.NoTickets

        // Participation safeguard: with one buyer, the top-3 weighted
        // draw still pays them 50% of the seeded pool — effectively a
        // free lift from the jackpot. Block payout below the configured
        // distinct-buyer threshold so the caller can cancel + refund
        // instead.
        val minBuyers = LotteryHelper.dailyMinBuyers(configService, guildId)
        val distinctBuyers = tickets.map { it.discordId }.toSet().size
        if (distinctBuyers < minBuyers) {
            return DrawOutcome.BelowMinBuyers(have = distinctBuyers, need = minBuyers)
        }

        // Winner slots never exceed the distinct buyer count (one ticket
        // row per buyer): with 2 buyers a 3-slot 50/30/20 schedule would
        // leak 20% of the pool back to the jackpot every draw — scaling
        // to 2 slots pays the full pot 60/40 instead. Matters most for
        // small guilds where the daily WEIGHTED draw has 2-3 buyers.
        val winnerSlots = lottery.winnerCount.coerceAtLeast(1).coerceAtMost(tickets.size)
        val multiplierTiers = LotteryHelper.volumeMultiplierTiers(configService, guildId)
        val winners = drawWinners(tickets, winnerSlots, random, multiplierTiers)
        val shares = prizeShares(winnerSlots, lottery.poolAmount)

        val payouts = mutableListOf<WinnerPayout>()
        var totalPaid = 0L
        winners.forEachIndexed { index, ticket ->
            val amount = shares.getOrNull(index) ?: 0L
            if (amount <= 0L) return@forEachIndexed
            val user = userService.getUserByIdForUpdate(ticket.discordId, guildId) ?: return@forEachIndexed
            user.socialCredit = (user.socialCredit ?: 0L) + amount
            userService.updateUser(user)
            jackpotService.recordWin(guildId, ticket.discordId, amount)
            payouts += WinnerPayout(ticket.discordId, ticket.ticketCount, amount)
            totalPaid += amount
            eventPublisher?.publishEvent(LotteryWonEvent(ticket.discordId, guildId, amount))
        }

        val drained = lottery.poolAmount
        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_DRAWN
        lotteryPersistence.upsert(lottery)

        // Any rounding remainder (e.g. odd-number split) goes back into
        // the per-guild jackpot pool rather than vanishing.
        val remainder = drained - totalPaid
        if (remainder > 0L) jackpotService.addToPool(guildId, remainder)

        return DrawOutcome.Ok(
            payouts = payouts,
            totalPaid = totalPaid,
            drained = drained,
            bonusTicketsAwarded = tickets.sumOf { it.bonusTickets.coerceAtLeast(0L) },
            highestMilestoneFired = lottery.milestonesFired,
        )
    }

    /**
     * Cancel the open TICKET_WEIGHTED lottery, refunding every buyer.
     * The undistributed seed returns to the jackpot pool by default;
     * with [rolloverPot] it is parked on the cancelled row instead, for
     * the next weighted open to claim (the daily job passes the guild's
     * `LOTTERY_DAILY_ROLLOVER_ENABLED`; the admin `/jackpotadmin cancel`
     * path keeps the default so a manually killed event returns its
     * seed immediately).
     */
    fun cancelLottery(guildId: Long, rolloverPot: Boolean = false): CancelOutcome {
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED
        ) ?: return CancelOutcome.NoOpenLottery
        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val tickets = lotteryPersistence.ticketsByLottery(lotteryId)

        var refundedTotal = 0L
        var refundedUsers = 0
        for (t in tickets) {
            if (t.spent <= 0L) continue
            val user = userService.getUserByIdForUpdate(t.discordId, guildId) ?: continue
            user.socialCredit = (user.socialCredit ?: 0L) + t.spent
            userService.updateUser(user)
            refundedTotal += t.spent
            refundedUsers++
        }

        val seedReturn = (lottery.poolAmount - refundedTotal).coerceAtLeast(0L)
        var returnedToPool = 0L
        var rolledOver = 0L
        if (seedReturn > 0L) {
            if (rolloverPot) {
                lottery.rolloverOut = seedReturn
                rolledOver = seedReturn
            } else {
                jackpotService.addToPool(guildId, seedReturn)
                returnedToPool = seedReturn
            }
        }

        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_CANCELLED
        lotteryPersistence.upsert(lottery)

        return CancelOutcome.Ok(
            refundedUsers, refundedTotal,
            returnedToPool = returnedToPool, rolledOver = rolledOver,
        )
    }

    /** Read-only summary for `/lottery status` and the moderation tab. */
    fun getOpenWeighted(guildId: Long): JackpotLotteryDto? =
        lotteryPersistence.getOpenByGuildAndMode(guildId, JackpotLotteryDto.MODE_TICKET_WEIGHTED)

    fun ticketsForOpenWeighted(guildId: Long): List<JackpotLotteryTicketDto> {
        val open = getOpenWeighted(guildId) ?: return emptyList()
        val id = open.id ?: return emptyList()
        return lotteryPersistence.ticketsByLottery(id)
    }

    // ===================================================================
    // NUMBER_MATCH methods (new)
    // ===================================================================

    /**
     * Open a NUMBER_MATCH daily lottery for [guildId]. Seeds the prize
     * pool with `floor(currentJackpot * seedPct/100)` plus any rollover
     * pot parked by the previous match draw, and stays open for
     * [durationHours]. Rejects if a NUMBER_MATCH lottery is already
     * open. Unlike weighted, an empty jackpot is OK — players' tickets
     * still grow the prize pool.
     *
     * [pickCount] / [numberMax] define the draw shape (default
     * Lotto-style Pick 5 of 1-49). Callers pass the guild's
     * `LOTTERY_DAILY_PICK_COUNT` / `LOTTERY_DAILY_NUMBER_MAX` so small
     * guilds can run winnable odds (e.g. pick 3 of 15). [numberMax] is
     * coerced to at least `2 × pickCount` so a degenerate near-
     * guaranteed-win range can't be configured.
     */
    fun openMatchLottery(
        guildId: Long,
        ticketPrice: Long,
        seedPct: Long,
        durationHours: Long,
        pickCount: Int = LotteryHelper.MATCH_PICK_COUNT,
        numberMax: Int = LotteryHelper.MATCH_NUMBER_MAX,
    ): OpenOutcome {
        if (ticketPrice <= 0L) return OpenOutcome.InvalidParams("ticket price must be > 0")
        if (durationHours <= 0L) return OpenOutcome.InvalidParams("duration must be > 0 hours")
        if (seedPct < 0L || seedPct > 100L) return OpenOutcome.InvalidParams("seed pct must be in [0, 100]")
        if (pickCount !in LotteryHelper.MIN_DAILY_PICK_COUNT..LotteryHelper.MAX_DAILY_PICK_COUNT) {
            return OpenOutcome.InvalidParams(
                "pick count must be in [${LotteryHelper.MIN_DAILY_PICK_COUNT}, ${LotteryHelper.MAX_DAILY_PICK_COUNT}]"
            )
        }
        val effectiveNumberMax = numberMax.coerceAtLeast(pickCount * 2)

        if (lotteryPersistence.getOpenByGuildAndModeForUpdate(
                guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
            ) != null
        ) return OpenOutcome.AlreadyOpen

        val poolBefore = jackpotService.getPool(guildId)
        // 0-pool is fine for NUMBER_MATCH — engagement-driven prize pool.
        val seed = if (poolBefore <= 0L || seedPct <= 0L) 0L else
            kotlin.math.floor(poolBefore * (seedPct.toDouble() / 100.0)).toLong().coerceAtMost(poolBefore)
        val drained = if (seed > 0L) drainFromPool(guildId, seed) else 0L
        val rolledIn = claimRollover(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)

        val now = Instant.now()
        val lottery = JackpotLotteryDto(
            guildId = guildId,
            ticketPrice = ticketPrice,
            poolAmount = drained + rolledIn,
            winnerCount = 0,                 // unused for NUMBER_MATCH
            openedAt = now,
            closesAt = now.plusSeconds(durationHours * 3600L),
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = pickCount,
            numberMax = effectiveNumberMax,
        )
        return OpenOutcome.Ok(lotteryPersistence.upsert(lottery), seeded = drained, rolledIn = rolledIn)
    }

    /**
     * Buy a NUMBER_MATCH ticket with a specific pick set. Picks must be
     * exactly [LotteryHelper.MATCH_PICK_COUNT] distinct ints in
     * `[1, MATCH_NUMBER_MAX]`. Each user can buy at most one ticket per
     * draw — repeated buys return [BuyMatchOutcome.AlreadyBought]; pick
     * what you want, you live with it.
     *
     * Revenue split: the configured
     * `LOTTERY_DAILY_REVENUE_JACKPOT_PCT` of the cost routes back to
     * the per-guild jackpot pool; the rest feeds today's prize pool.
     * The 30/70 default makes the daily a credit sink while letting
     * engagement grow the prize pool.
     */
    fun buyMatchTicket(guildId: Long, discordId: Long, picks: List<Int>): BuyMatchOutcome {
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
        ) ?: return BuyMatchOutcome.NoOpenLottery

        // Validate against the open row's shape (not the live config):
        // an admin editing pick count mid-draw must not orphan tickets
        // already bought against the old shape.
        val validation = validatePicks(picks, lottery.pickCount, lottery.numberMax)
        if (validation != null) return BuyMatchOutcome.InvalidPicks(validation)

        val lotteryId = lottery.id ?: error("Open lottery has no id")
        if (lotteryPersistence.getTicketForUpdate(lotteryId, discordId) != null) {
            return BuyMatchOutcome.AlreadyBought
        }

        val cost = lottery.ticketPrice
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BuyMatchOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < cost) return BuyMatchOutcome.Insufficient(have = balance, need = cost)

        user.socialCredit = balance - cost
        userService.updateUser(user)

        val sortedPicks = picks.sorted()
        lotteryPersistence.upsertTicket(
            JackpotLotteryTicketDto(
                lotteryId = lotteryId,
                discordId = discordId,
                ticketCount = 1,
                spent = cost,
                pickedNumbers = sortedPicks.joinToString(","),
            )
        )

        val jackpotPct = LotteryHelper.dailyRevenueJackpotPct(configService, guildId)
        val toJackpot = kotlin.math.floor(cost * (jackpotPct.toDouble() / 100.0)).toLong().coerceIn(0L, cost)
        val toPrize = cost - toJackpot

        if (toPrize > 0L) {
            lottery.poolAmount += toPrize
            lotteryPersistence.upsert(lottery)
        }
        if (toJackpot > 0L) {
            jackpotService.addToPool(guildId, toJackpot)
        }

        val streak = recordParticipationAndAward(guildId, discordId, user)

        return BuyMatchOutcome.Ok(
            pickedNumbers = sortedPicks,
            totalSpent = cost,
            newBalance = user.socialCredit ?: (balance - cost),
            newPool = lottery.poolAmount,
            jackpotInflow = toJackpot,
            streakDays = streak.days,
            streakBonusAwarded = streak.bonusAwarded,
        )
    }

    /**
     * Draw a NUMBER_MATCH lottery: pick the row's `pick_count` winning
     * numbers, compute match counts per ticket, distribute the prize
     * pool by tier.
     *
     * Tier shares follow [LotteryHelper.matchTierPcts] for the row's
     * pick count — e.g. the 5-pick default pays 5/4/3/2 matches
     * 60/25/10/5 % of the pool; a small-guild 3-pick draw pays 3/2
     * matches 70/30. 0 / 1 matches always win nothing — sink for the
     * day.
     *
     * Empty tiers and rounding remainders roll back into the per-guild
     * jackpot pool so credits never vanish — or, with
     * [rolloverRemainder], are parked on the drawn row as the rollover
     * pot for the next open to claim.
     *
     * Returns [DrawMatchOutcome.NoTickets] when no one bought today —
     * the caller (scheduler) is expected to refund the seed in that
     * case via [cancelMatchLottery].
     */
    fun drawMatchLottery(guildId: Long, rolloverRemainder: Boolean = false): DrawMatchOutcome {
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
        ) ?: return DrawMatchOutcome.NoOpenLottery
        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val tickets = lotteryPersistence.ticketsByLottery(lotteryId)
        if (tickets.isEmpty()) return DrawMatchOutcome.NoTickets

        // Participation safeguard. NUMBER_MATCH is somewhat self-protecting
        // at low counts (random rarely favours a solo buyer), but still
        // enforce the threshold uniformly so the moderation surface
        // means the same thing in both modes.
        val minBuyers = LotteryHelper.dailyMinBuyers(configService, guildId)
        val distinctBuyers = tickets.map { it.discordId }.toSet().size
        if (distinctBuyers < minBuyers) {
            return DrawMatchOutcome.BelowMinBuyers(have = distinctBuyers, need = minBuyers)
        }

        val drawn = drawNumbers(lottery.numberMax, lottery.pickCount, random)
        val drawnSet = drawn.toSet()

        // Group tickets by match count (0..pickCount).
        val byMatches = (0..lottery.pickCount).associateWith { mutableListOf<JackpotLotteryTicketDto>() }
        for (ticket in tickets) {
            val picks = parsePicks(ticket.pickedNumbers)
            val matches = picks.count { it in drawnSet }
            byMatches[matches]?.add(ticket)
        }

        val totalPool = lottery.poolAmount
        // Tier schedule follows the row's pick count (pay pickCount..2
        // matches) so a small-guild pick-3 draw pays 70/30 instead of
        // leaving the 5-pick tiers unreachable.
        val effectivePickCount = lottery.pickCount
            .coerceIn(LotteryHelper.MIN_DAILY_PICK_COUNT, LotteryHelper.MAX_DAILY_PICK_COUNT)
        val tierShares = computeTierShares(totalPool, LotteryHelper.matchTierPcts(effectivePickCount))
        val payouts = mutableListOf<MatchTierPayout>()
        var totalPaid = 0L

        // Tier order: pickCount, pickCount-1, …, 2 matches → indexes 0.. in tierShares.
        val tierMatchCounts = (effectivePickCount downTo 2).toList()
        tierMatchCounts.forEachIndexed { tierIndex, matchCount ->
            val share = tierShares[tierIndex]
            if (share <= 0L) return@forEachIndexed
            val winners = byMatches[matchCount].orEmpty()
            if (winners.isEmpty()) return@forEachIndexed
            val perWinner = share / winners.size
            if (perWinner <= 0L) return@forEachIndexed
            for (ticket in winners) {
                val user = userService.getUserByIdForUpdate(ticket.discordId, guildId) ?: continue
                user.socialCredit = (user.socialCredit ?: 0L) + perWinner
                userService.updateUser(user)
                jackpotService.recordWin(guildId, ticket.discordId, perWinner)
                payouts += MatchTierPayout(ticket.discordId, matchCount, perWinner)
                totalPaid += perWinner
                eventPublisher?.publishEvent(LotteryWonEvent(ticket.discordId, guildId, perWinner))
            }
        }

        lottery.drawnNumbers = drawn.joinToString(",")
        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_DRAWN

        // Unpaid remainder (empty tiers + rounding crumbs): back to the
        // jackpot by default, or parked as the rollover pot so
        // tomorrow's prize pool visibly grows on a no-winner day.
        val remainder = totalPool - totalPaid
        var rolledBack = 0L
        var rolledOver = 0L
        if (remainder > 0L) {
            if (rolloverRemainder) {
                lottery.rolloverOut = remainder
                rolledOver = remainder
            } else {
                jackpotService.addToPool(guildId, remainder)
                rolledBack = remainder
            }
        }
        lotteryPersistence.upsert(lottery)

        return DrawMatchOutcome.Ok(
            drawnNumbers = drawn,
            tierPayouts = payouts,
            totalPaid = totalPaid,
            drained = totalPool,
            rolledBackToJackpot = rolledBack,
            rolledOver = rolledOver,
        )
    }

    /**
     * Cancel an open NUMBER_MATCH lottery: refund every buyer their
     * spend (the prize portion of their ticket cost — the jackpot
     * portion already left the user's wallet on buy and isn't here to
     * refund). Returns the seed share to the per-guild jackpot pool,
     * or — with [rolloverPot] — parks it on the cancelled row for the
     * next match open to claim.
     *
     * Used by the scheduler when a draw rolls but no tickets were
     * bought (no one to draw against) or participation was below the
     * min-buyer threshold.
     */
    fun cancelMatchLottery(guildId: Long, rolloverPot: Boolean = false): CancelOutcome {
        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
        ) ?: return CancelOutcome.NoOpenLottery
        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val tickets = lotteryPersistence.ticketsByLottery(lotteryId)

        var refundedTotal = 0L
        var refundedUsers = 0
        for (t in tickets) {
            if (t.spent <= 0L) continue
            val user = userService.getUserByIdForUpdate(t.discordId, guildId) ?: continue
            user.socialCredit = (user.socialCredit ?: 0L) + t.spent
            userService.updateUser(user)
            refundedTotal += t.spent
            refundedUsers++
        }

        val seedReturn = (lottery.poolAmount - refundedTotal).coerceAtLeast(0L)
        var returnedToPool = 0L
        var rolledOver = 0L
        if (seedReturn > 0L) {
            if (rolloverPot) {
                lottery.rolloverOut = seedReturn
                rolledOver = seedReturn
            } else {
                jackpotService.addToPool(guildId, seedReturn)
                returnedToPool = seedReturn
            }
        }

        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_CANCELLED
        lotteryPersistence.upsert(lottery)

        return CancelOutcome.Ok(
            refundedUsers, refundedTotal,
            returnedToPool = returnedToPool, rolledOver = rolledOver,
        )
    }

    /** Read-only: current open NUMBER_MATCH lottery for [guildId], if any. */
    fun getOpenMatch(guildId: Long): JackpotLotteryDto? =
        lotteryPersistence.getOpenByGuildAndMode(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)

    /** Read-only: most-recent NUMBER_MATCH row (any status) for the result panel. */
    fun getLatestMatch(guildId: Long): JackpotLotteryDto? =
        lotteryPersistence.getLatestByGuildAndMode(guildId, JackpotLotteryDto.MODE_NUMBER_MATCH)

    /** Read-only: tickets for the current open NUMBER_MATCH lottery. */
    fun ticketsForOpenMatch(guildId: Long): List<JackpotLotteryTicketDto> {
        val open = getOpenMatch(guildId) ?: return emptyList()
        val id = open.id ?: return emptyList()
        return lotteryPersistence.ticketsByLottery(id)
    }

    /** This user's ticket for the current open NUMBER_MATCH, if bought. */
    fun userTicketForOpenMatch(guildId: Long, discordId: Long): JackpotLotteryTicketDto? {
        val open = getOpenMatch(guildId) ?: return null
        val id = open.id ?: return null
        return lotteryPersistence.ticketsByLottery(id).firstOrNull { it.discordId == discordId }
    }

    // ===================================================================
    // Announcement-message bookkeeping (used by LotteryAnnouncer +
    // LotteryRefreshJob to track the Discord message we posted so we
    // can edit it later when the pool grows).
    // ===================================================================

    /**
     * Persist the channel + message ids of the announce embed and the
     * pool value at announce time. Called once per cycle by
     * [bot.toby.scheduling.LotteryAnnouncer.announceCycle] after the
     * message ships. No-op when [lotteryId] no longer points at a row
     * (the close-then-reopen tick already moved on).
     */
    fun recordAnnouncement(
        lotteryId: Long,
        channelId: Long,
        messageId: Long,
        pool: Long,
        incentivesDigest: String? = null,
    ) {
        val lottery = lotteryPersistence.findById(lotteryId) ?: return
        lottery.announcementChannelId = channelId
        lottery.announcementMessageId = messageId
        lottery.announcedPoolAmount = pool
        lottery.announcedIncentivesDigest = incentivesDigest
        lotteryPersistence.upsert(lottery)
    }

    /**
     * Clear the announcement reference. Called by the refresh job when
     * an edit attempt returns UNKNOWN_MESSAGE — the moderator deleted
     * the announce, so further refresh attempts would be wasted.
     */
    fun clearAnnouncement(lotteryId: Long) {
        val lottery = lotteryPersistence.findById(lotteryId) ?: return
        lottery.announcementChannelId = null
        lottery.announcementMessageId = null
        lottery.announcedPoolAmount = null
        lottery.announcedIncentivesDigest = null
        lotteryPersistence.upsert(lottery)
    }

    /**
     * Bump the announce-time watermarks (pool + incentives digest)
     * after a successful refresh edit so subsequent ticks short-circuit
     * until something actually changes. The digest covers the
     * participation-incentive tiers the embed displays — a mid-lottery
     * tier edit in the web UI bumps it even when the pool is flat.
     */
    fun updateAnnouncementWatermarks(lotteryId: Long, pool: Long, incentivesDigest: String?) {
        val lottery = lotteryPersistence.findById(lotteryId) ?: return
        lottery.announcedPoolAmount = pool
        lottery.announcedIncentivesDigest = incentivesDigest
        lotteryPersistence.upsert(lottery)
    }

    /**
     * All open lotteries for [guildId] across both modes. Used by
     * [bot.toby.scheduling.LotteryRefreshJob] to fan out the per-guild
     * refresh tick.
     */
    fun getOpenLotteriesForRefresh(guildId: Long): List<JackpotLotteryDto> =
        listOfNotNull(getOpenWeighted(guildId), getOpenMatch(guildId))

    // ===================================================================
    // Internal helpers (testable)
    // ===================================================================

    /**
     * Pick [count] distinct winners by ticket-weighted draw without
     * replacement. Used by TICKET_WEIGHTED draws.
     *
     * Each ticket's draw weight is its [effectiveWeight]: paid
     * ticket_count + accumulated bulk-buy bonus + volume-multiplier
     * uplift (when [multiplierTiers] is non-empty). When no incentives
     * are configured the effective weight collapses to ticket_count
     * and the draw matches the pre-incentive behaviour exactly.
     */
    internal fun drawWinners(
        tickets: List<JackpotLotteryTicketDto>,
        count: Int,
        random: Random,
        multiplierTiers: List<Pair<Long, Int>> = emptyList(),
    ): List<JackpotLotteryTicketDto> {
        val remaining = tickets.toMutableList()
        val weights = remaining.map { effectiveWeight(it, multiplierTiers) }.toMutableList()
        val winners = mutableListOf<JackpotLotteryTicketDto>()
        repeat(count) {
            if (remaining.isEmpty()) return@repeat
            val totalWeight = weights.sum()
            if (totalWeight <= 0L) return@repeat
            var roll = random.nextLong(totalWeight)
            for (i in remaining.indices) {
                val w = weights[i]
                if (roll < w) {
                    winners += remaining.removeAt(i)
                    weights.removeAt(i)
                    return@repeat
                }
                roll -= w
            }
        }
        return winners
    }

    /**
     * Effective draw weight for [ticket] given the guild's volume
     * multiplier tiers. Bonus tickets are added 1:1; the multiplier
     * scales the paid ticket count, *not* the bonus tickets (bonuses
     * are flat rewards, not weight-stacked further on themselves).
     * Floors fractional credit, so a 1.25× of 5 tickets is 5 + 1 = 6.
     */
    internal fun effectiveWeight(
        ticket: JackpotLotteryTicketDto,
        multiplierTiers: List<Pair<Long, Int>>,
    ): Long {
        val paid = ticket.ticketCount.toLong()
        val bonus = ticket.bonusTickets.coerceAtLeast(0L)
        if (multiplierTiers.isEmpty()) return paid + bonus
        val bp = LotteryHelper.multiplierBpFor(paid, multiplierTiers)
        val multiplierBonus = paid * (bp - LotteryHelper.MULTIPLIER_BP_IDENTITY) /
            LotteryHelper.MULTIPLIER_BP_IDENTITY.toLong()
        return paid + bonus + multiplierBonus.coerceAtLeast(0L)
    }

    /**
     * Split [pool] across [slots] winner slots for TICKET_WEIGHTED.
     * Schedule:
     *  - 1 winner: 100
     *  - 2 winners: 60/40
     *  - 3 winners: 50/30/20
     *  - 4 winners: 40/30/20/10
     *  - 5+ winners: linear taper that always sums to 100
     */
    internal fun prizeShares(slots: Int, pool: Long): List<Long> {
        if (slots <= 0 || pool <= 0L) return emptyList()
        val pcts: DoubleArray = when (slots) {
            1 -> doubleArrayOf(1.0)
            2 -> doubleArrayOf(0.6, 0.4)
            3 -> doubleArrayOf(0.5, 0.3, 0.2)
            4 -> doubleArrayOf(0.4, 0.3, 0.2, 0.1)
            else -> {
                val raw = DoubleArray(slots) { (slots - it).toDouble() }
                val sum = raw.sum().coerceAtLeast(1.0)
                DoubleArray(slots) { raw[it] / sum }
            }
        }
        return pcts.map { max(0L, kotlin.math.floor(pool * it).toLong()) }
    }

    /**
     * Pick [count] distinct numbers in `[1, max]` for a NUMBER_MATCH
     * draw. Result is sorted ascending for stable display in the
     * `drawn_numbers` column and matched-pick UI.
     */
    internal fun drawNumbers(max: Int, count: Int, random: Random): List<Int> {
        require(count in 1..max) { "count must be in 1..max ($count, $max)" }
        val pool = (1..max).toMutableList()
        val out = mutableListOf<Int>()
        repeat(count) {
            val index = random.nextInt(pool.size)
            out += pool.removeAt(index)
        }
        return out.sorted()
    }

    /**
     * Convert tier percentages [60, 25, 10, 5] to per-tier credit
     * amounts using `floor` (rounding remainder rolls to jackpot).
     */
    internal fun computeTierShares(pool: Long, tierPcts: IntArray): LongArray {
        if (pool <= 0L) return LongArray(tierPcts.size)
        return LongArray(tierPcts.size) { i ->
            kotlin.math.floor(pool * (tierPcts[i].toDouble() / 100.0)).toLong().coerceAtLeast(0L)
        }
    }

    /**
     * Validate a NUMBER_MATCH pick set. Returns null when valid; an
     * error reason string otherwise. Centralised so the controller can
     * surface a friendly 400 message.
     */
    internal fun validatePicks(picks: List<Int>, pickCount: Int, numberMax: Int): String? {
        if (picks.size != pickCount) return "must select exactly $pickCount numbers"
        if (picks.toSet().size != picks.size) return "picks must be distinct"
        val outOfRange = picks.firstOrNull { it < 1 || it > numberMax }
        if (outOfRange != null) return "picks must be in 1..$numberMax (got $outOfRange)"
        return null
    }

    /**
     * Parse the comma-separated `picked_numbers` column on a ticket
     * row. Returns an empty list when null/blank — the row is
     * effectively a non-pick (which counts as 0 matches).
     */
    internal fun parsePicks(csv: String?): List<Int> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Rollover pot still parked on the most recent closed lottery of
     * (guild, mode), without claiming it. Used by [openLottery] to
     * decide whether a dry jackpot can still open.
     */
    private fun peekRollover(guildId: Long, mode: String): Long =
        lotteryPersistence.getLatestByGuildAndMode(guildId, mode)?.rolloverOut?.coerceAtLeast(0L) ?: 0L

    /**
     * Claim (and zero) the rollover pot parked on the most recent
     * closed lottery of (guild, mode). Claimed unconditionally at open
     * — even when the guild has since disabled the rollover toggle —
     * so parked credits can never strand on an old row.
     */
    private fun claimRollover(guildId: Long, mode: String): Long {
        val latest = lotteryPersistence.getLatestByGuildAndMode(guildId, mode) ?: return 0L
        val amount = latest.rolloverOut
        if (amount <= 0L) return 0L
        latest.rolloverOut = 0L
        lotteryPersistence.upsert(latest)
        return amount
    }

    /** Result of a per-buy streak update. [days] = 0 when tracking is unavailable. */
    internal data class StreakProgress(val days: Int, val bonusAwarded: Long)

    /**
     * Record a lottery participation for (guild, user) on today's UTC
     * date and, when the resulting streak is at/above the configured
     * `LOTTERY_STREAK_DAYS` threshold, pay the `LOTTERY_STREAK_BONUS`
     * once for the day — drained from the jackpot pool so the bonus
     * never mints credits (an empty jackpot pays 0 but the streak
     * still advances).
     *
     * Same-day repeat buys are no-ops (streak unchanged, no second
     * bonus): the award only fires on the transition to a new
     * participation day. Buying on the day after the last recorded one
     * extends the streak; any gap resets it to 1.
     */
    private fun recordParticipationAndAward(
        guildId: Long,
        discordId: Long,
        user: UserDto,
    ): StreakProgress {
        val persistence = streakPersistence ?: return StreakProgress(days = 0, bonusAwarded = 0L)
        val today = LocalDate.now(clock)
        val row = persistence.getForUpdate(guildId, discordId)
            ?: LotteryStreakDto(guildId = guildId, discordId = discordId)

        val last = row.lastParticipationDate
        if (last == today) return StreakProgress(days = row.streakDays, bonusAwarded = 0L)

        row.streakDays = if (last == today.minusDays(1)) row.streakDays + 1 else 1
        row.lastParticipationDate = today
        persistence.upsert(row)

        val threshold = LotteryHelper.streakThresholdDays(configService, guildId)
        val bonus = LotteryHelper.streakBonusCredits(configService, guildId)
        var awarded = 0L
        if (threshold > 0 && bonus > 0L && row.streakDays >= threshold) {
            awarded = drainFromPool(guildId, bonus)
            if (awarded > 0L) {
                user.socialCredit = (user.socialCredit ?: 0L) + awarded
                userService.updateUser(user)
            }
        }
        return StreakProgress(days = row.streakDays, bonusAwarded = awarded)
    }

    /**
     * Decrement the per-guild jackpot row by [amount]. Mirrors the
     * lock+mutate+upsert idiom in `JackpotService.awardJackpot`.
     */
    private fun drainFromPool(guildId: Long, amount: Long): Long {
        if (amount <= 0L) return 0L
        // resetPool→addToPool keeps the pool atomically non-gappy and
        // matches the pattern used elsewhere in the codebase.
        val pool = jackpotService.resetPool(guildId)
        val drained = amount.coerceAtMost(pool)
        val leftover = pool - drained
        if (leftover > 0L) jackpotService.addToPool(guildId, leftover)
        return drained
    }
}
