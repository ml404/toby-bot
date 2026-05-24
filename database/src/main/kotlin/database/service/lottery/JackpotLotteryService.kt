package database.service.lottery

import common.events.lottery.LotteryWonEvent
import database.dto.lottery.JackpotLotteryDto
import database.dto.lottery.JackpotLotteryTicketDto
import database.persistence.lottery.JackpotLotteryPersistence
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.max
import kotlin.random.Random
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
 *   - **NUMBER_MATCH** (daily auto-drain — Pick 5 of 1-49).
 *     [openMatchLottery] / [buyMatchTicket] / [drawMatchLottery].
 *     Players pick 5 distinct numbers from 1-49; the draw produces 5
 *     winning numbers; payouts tier by match count
 *     (5/4/3/2 → 60/25/10/5 % of pool). 0 / 1 matches pay nothing —
 *     those tickets are the credit sink.
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
) {

    // ===================================================================
    // TICKET_WEIGHTED outcomes (existing)
    // ===================================================================

    sealed interface OpenOutcome {
        data class Ok(val lottery: JackpotLotteryDto, val seeded: Long) : OpenOutcome
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
        data class Ok(val refundedUsers: Int, val refundedTotal: Long, val returnedToPool: Long) : CancelOutcome
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
        if (poolBefore == 0L) return OpenOutcome.EmptyPool
        val seed = kotlin.math.floor(poolBefore * drainPct).toLong().coerceAtMost(poolBefore).coerceAtLeast(1L)

        val drained = drainFromPool(guildId, seed)

        val now = Instant.now()
        val lottery = JackpotLotteryDto(
            guildId = guildId,
            ticketPrice = ticketPrice,
            poolAmount = drained,
            winnerCount = winnerCount,
            openedAt = now,
            closesAt = now.plusSeconds(durationHours * 3600L),
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        return OpenOutcome.Ok(lotteryPersistence.upsert(lottery), seeded = drained)
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

        return BuyOutcome.Ok(
            ticketCount = updatedTicket.ticketCount,
            totalSpent = updatedTicket.spent,
            newBalance = user.socialCredit ?: (balance - cost),
            newPool = lottery.poolAmount,
            bonusTicketsGranted = bulkBonus,
            totalBonusTickets = updatedTicket.bonusTickets,
            milestoneBonuses = firedBonuses,
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

        val winnerSlots = lottery.winnerCount.coerceAtLeast(1)
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

    fun cancelLottery(guildId: Long): CancelOutcome {
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
        if (seedReturn > 0L) jackpotService.addToPool(guildId, seedReturn)

        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_CANCELLED
        lotteryPersistence.upsert(lottery)

        return CancelOutcome.Ok(refundedUsers, refundedTotal, returnedToPool = seedReturn)
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
     * pool with `floor(currentJackpot * seedPct/100)`, sets pick_count =
     * 5 and number_max = 49 (Lotto-style), and stays open for
     * [durationHours]. Rejects if a NUMBER_MATCH lottery is already
     * open. Unlike weighted, an empty jackpot is OK — players' tickets
     * still grow the prize pool.
     */
    fun openMatchLottery(
        guildId: Long,
        ticketPrice: Long,
        seedPct: Long,
        durationHours: Long,
    ): OpenOutcome {
        if (ticketPrice <= 0L) return OpenOutcome.InvalidParams("ticket price must be > 0")
        if (durationHours <= 0L) return OpenOutcome.InvalidParams("duration must be > 0 hours")
        if (seedPct < 0L || seedPct > 100L) return OpenOutcome.InvalidParams("seed pct must be in [0, 100]")

        if (lotteryPersistence.getOpenByGuildAndModeForUpdate(
                guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
            ) != null
        ) return OpenOutcome.AlreadyOpen

        val poolBefore = jackpotService.getPool(guildId)
        // 0-pool is fine for NUMBER_MATCH — engagement-driven prize pool.
        val seed = if (poolBefore <= 0L || seedPct <= 0L) 0L else
            kotlin.math.floor(poolBefore * (seedPct.toDouble() / 100.0)).toLong().coerceAtMost(poolBefore)
        val drained = if (seed > 0L) drainFromPool(guildId, seed) else 0L

        val now = Instant.now()
        val lottery = JackpotLotteryDto(
            guildId = guildId,
            ticketPrice = ticketPrice,
            poolAmount = drained,
            winnerCount = 0,                 // unused for NUMBER_MATCH
            openedAt = now,
            closesAt = now.plusSeconds(durationHours * 3600L),
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_NUMBER_MATCH,
            pickCount = LotteryHelper.MATCH_PICK_COUNT,
            numberMax = LotteryHelper.MATCH_NUMBER_MAX,
        )
        return OpenOutcome.Ok(lotteryPersistence.upsert(lottery), seeded = drained)
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
        val pickCount = LotteryHelper.MATCH_PICK_COUNT
        val numberMax = LotteryHelper.MATCH_NUMBER_MAX
        val validation = validatePicks(picks, pickCount, numberMax)
        if (validation != null) return BuyMatchOutcome.InvalidPicks(validation)

        val lottery = lotteryPersistence.getOpenByGuildAndModeForUpdate(
            guildId, JackpotLotteryDto.MODE_NUMBER_MATCH
        ) ?: return BuyMatchOutcome.NoOpenLottery

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

        return BuyMatchOutcome.Ok(
            pickedNumbers = sortedPicks,
            totalSpent = cost,
            newBalance = user.socialCredit ?: (balance - cost),
            newPool = lottery.poolAmount,
            jackpotInflow = toJackpot,
        )
    }

    /**
     * Draw a NUMBER_MATCH lottery: pick 5 winning numbers, compute
     * match counts per ticket, distribute the prize pool by tier.
     *
     * Tier shares (60/25/10/5 % of pool, see [LotteryHelper.TIER_PCTS_5_4_3_2]):
     *   - 5/5 matches share 60% equally
     *   - 4/5 share 25% equally
     *   - 3/5 share 10% equally
     *   - 2/5 share 5% equally
     *   - 0/5 and 1/5 win nothing — sink for the day.
     *
     * Empty tiers and rounding remainders roll back into the per-guild
     * jackpot pool so credits never vanish.
     *
     * Returns [DrawMatchOutcome.NoTickets] when no one bought today —
     * the caller (scheduler) is expected to refund the seed in that
     * case via [cancelMatchLottery].
     */
    fun drawMatchLottery(guildId: Long): DrawMatchOutcome {
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
        val tierShares = computeTierShares(totalPool, LotteryHelper.TIER_PCTS_5_4_3_2)
        val payouts = mutableListOf<MatchTierPayout>()
        var totalPaid = 0L

        // Tier order: 5, 4, 3, 2 matches → indexes 0..3 in tierShares.
        val tierMatchCounts = listOf(5, 4, 3, 2)
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
        lotteryPersistence.upsert(lottery)

        val remainder = totalPool - totalPaid
        val rolledBack = if (remainder > 0L) {
            jackpotService.addToPool(guildId, remainder)
            remainder
        } else 0L

        return DrawMatchOutcome.Ok(
            drawnNumbers = drawn,
            tierPayouts = payouts,
            totalPaid = totalPaid,
            drained = totalPool,
            rolledBackToJackpot = rolledBack,
        )
    }

    /**
     * Cancel an open NUMBER_MATCH lottery: refund every buyer their
     * spend (the prize portion of their ticket cost — the jackpot
     * portion already left the user's wallet on buy and isn't here to
     * refund). Returns the seed share to the per-guild jackpot pool.
     *
     * Used by the scheduler when a draw rolls but no tickets were
     * bought (no one to draw against).
     */
    fun cancelMatchLottery(guildId: Long): CancelOutcome {
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
        if (seedReturn > 0L) jackpotService.addToPool(guildId, seedReturn)

        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_CANCELLED
        lotteryPersistence.upsert(lottery)

        return CancelOutcome.Ok(refundedUsers, refundedTotal, returnedToPool = seedReturn)
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
