package database.service

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.persistence.JackpotLotteryPersistence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.max
import kotlin.random.Random

/**
 * Per-guild jackpot-pool lottery events.
 *
 * Admins fire one of these to drain a runaway jackpot pool through a
 * ticketed multi-winner draw rather than letting one casino-roll
 * winner sweep the whole thing. Workflow:
 *
 *   1. [openLottery] — admin sets ticket price, duration, winner count
 *      and what fraction of the current pool seeds the prize. The seed
 *      amount is moved out of the per-guild jackpot row and parked on
 *      the lottery row's `pool_amount`.
 *   2. [buyTickets] — players spend credits; each purchase increments
 *      their ticket row and adds the spend to the lottery's prize
 *      `pool_amount` (so the prize grows with engagement).
 *   3. [drawLottery] — admin closes the lottery: weighted draw without
 *      replacement (probability ∝ ticket_count) picks `winner_count`
 *      winners, prize splits 50/30/20-style across them, winners are
 *      credited and recorded against [JackpotService.recordWin] so the
 *      cooldown gate covers both lottery and casino-roll wins.
 *   4. [cancelLottery] — admin cancels: every ticket buyer is refunded
 *      what they spent, the seed `pool_amount` returns to the per-guild
 *      jackpot row, and the lottery is marked CANCELLED.
 *
 * Concurrency: every mutation runs inside a `@Transactional` boundary
 * with a pessimistic write lock on the lottery row. Per-user ticket
 * rows are also locked on update so two simultaneous /lottery buy calls
 * from the same user can't double-spend their balance. Only one OPEN
 * lottery may exist per guild at a time, enforced by a partial unique
 * index in V27.
 */
@Service
@Transactional
class JackpotLotteryService(
    private val lotteryPersistence: JackpotLotteryPersistence,
    private val jackpotService: JackpotService,
    private val userService: UserService,
    private val random: Random = Random.Default,
) {

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
        ) : BuyOutcome
        data object NoOpenLottery : BuyOutcome
        data class InvalidCount(val ticketCount: Int) : BuyOutcome
        data class Insufficient(val have: Long, val need: Long) : BuyOutcome
        data object UnknownUser : BuyOutcome
    }

    sealed interface DrawOutcome {
        data class Ok(
            val payouts: List<WinnerPayout>,
            val totalPaid: Long,
            val drained: Long,
        ) : DrawOutcome
        data object NoOpenLottery : DrawOutcome
        data object NoTickets : DrawOutcome
    }

    sealed interface CancelOutcome {
        data class Ok(val refundedUsers: Int, val refundedTotal: Long, val returnedToPool: Long) : CancelOutcome
        data object NoOpenLottery : CancelOutcome
    }

    data class WinnerPayout(val discordId: Long, val ticketCount: Int, val amount: Long)

    /**
     * Open a new lottery for [guildId]. Pulls `floor(currentPool * drainPct)`
     * out of the per-guild jackpot pool and parks it on the lottery row.
     * Rejects if a lottery is already open, params are invalid, or the
     * pool is empty (nothing to drain).
     */
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

        if (lotteryPersistence.getOpenByGuildForUpdate(guildId) != null) return OpenOutcome.AlreadyOpen

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
        )
        return OpenOutcome.Ok(lotteryPersistence.upsert(lottery), seeded = drained)
    }

    /**
     * Buy [ticketCount] tickets in the open lottery for [guildId].
     * Debits the user's social_credit, increments the ticket row, and
     * adds the spend to the lottery's prize pool.
     */
    fun buyTickets(guildId: Long, discordId: Long, ticketCount: Int): BuyOutcome {
        if (ticketCount <= 0) return BuyOutcome.InvalidCount(ticketCount)
        val lottery = lotteryPersistence.getOpenByGuildForUpdate(guildId)
            ?: return BuyOutcome.NoOpenLottery

        val cost = lottery.ticketPrice * ticketCount.toLong()

        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return BuyOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < cost) return BuyOutcome.Insufficient(have = balance, need = cost)

        user.socialCredit = balance - cost
        userService.updateUser(user)

        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val existing = lotteryPersistence.getTicketForUpdate(lotteryId, discordId)
        val updatedTicket = if (existing == null) {
            JackpotLotteryTicketDto(
                lotteryId = lotteryId,
                discordId = discordId,
                ticketCount = ticketCount,
                spent = cost,
            )
        } else {
            existing.ticketCount += ticketCount
            existing.spent += cost
            existing
        }
        lotteryPersistence.upsertTicket(updatedTicket)

        lottery.poolAmount += cost
        lotteryPersistence.upsert(lottery)

        return BuyOutcome.Ok(
            ticketCount = updatedTicket.ticketCount,
            totalSpent = updatedTicket.spent,
            newBalance = user.socialCredit ?: (balance - cost),
            newPool = lottery.poolAmount,
        )
    }

    /**
     * Close the open lottery for [guildId]. Picks `winner_count` winners
     * via weighted draw without replacement; splits `pool_amount`
     * across them with a 50/30/20-style schedule (single winner gets
     * 100 %); credits each, records the wins for cooldown, and marks
     * the lottery DRAWN.
     */
    fun drawLottery(guildId: Long): DrawOutcome {
        val lottery = lotteryPersistence.getOpenByGuildForUpdate(guildId)
            ?: return DrawOutcome.NoOpenLottery
        val lotteryId = lottery.id ?: error("Open lottery has no id")
        val tickets = lotteryPersistence.ticketsByLottery(lotteryId)
        if (tickets.isEmpty() || tickets.sumOf { it.ticketCount.toLong() } == 0L) return DrawOutcome.NoTickets

        val winnerSlots = lottery.winnerCount.coerceAtLeast(1)
        val winners = drawWinners(tickets, winnerSlots, random)
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
        }

        val drained = lottery.poolAmount
        lottery.poolAmount = 0L
        lottery.status = JackpotLotteryDto.STATUS_DRAWN
        lotteryPersistence.upsert(lottery)

        // Any rounding remainder (e.g. odd-number split) goes back into
        // the per-guild jackpot pool rather than vanishing.
        val remainder = drained - totalPaid
        if (remainder > 0L) jackpotService.addToPool(guildId, remainder)

        return DrawOutcome.Ok(payouts = payouts, totalPaid = totalPaid, drained = drained)
    }

    /**
     * Cancel the open lottery for [guildId]: refund every buyer the
     * credits they spent, return `pool_amount` to the per-guild
     * jackpot pool, and mark the row CANCELLED.
     */
    fun cancelLottery(guildId: Long): CancelOutcome {
        val lottery = lotteryPersistence.getOpenByGuildForUpdate(guildId)
            ?: return CancelOutcome.NoOpenLottery
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
    fun getOpen(guildId: Long): JackpotLotteryDto? = lotteryPersistence.getOpenByGuild(guildId)

    fun ticketsForOpen(guildId: Long): List<JackpotLotteryTicketDto> {
        val open = lotteryPersistence.getOpenByGuild(guildId) ?: return emptyList()
        val id = open.id ?: return emptyList()
        return lotteryPersistence.ticketsByLottery(id)
    }

    /**
     * Pick [count] winners by ticket-weighted draw without replacement.
     * The same physical ticket can never win twice; once a holder is
     * picked, their entire ticket count is removed from the bag for the
     * subsequent picks. If [count] exceeds the number of distinct
     * holders, the result is shorter than [count] (the prize for the
     * unfilled slots rolls back into the per-guild pool via the
     * remainder-handling in [drawLottery]).
     */
    internal fun drawWinners(
        tickets: List<JackpotLotteryTicketDto>,
        count: Int,
        random: Random,
    ): List<JackpotLotteryTicketDto> {
        val remaining = tickets.toMutableList()
        val winners = mutableListOf<JackpotLotteryTicketDto>()
        repeat(count) {
            if (remaining.isEmpty()) return@repeat
            val totalWeight = remaining.sumOf { it.ticketCount.toLong() }
            if (totalWeight <= 0L) return@repeat
            var roll = random.nextLong(totalWeight)
            val it = remaining.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (roll < t.ticketCount) {
                    winners += t
                    it.remove()
                    return@repeat
                }
                roll -= t.ticketCount
            }
        }
        return winners
    }

    /**
     * Split [pool] across [slots] winner slots. Schedule:
     *  - 1 winner: 100
     *  - 2 winners: 60/40
     *  - 3 winners: 50/30/20
     *  - 4 winners: 40/30/20/10
     *  - 5+ winners: linear taper that always sums to 100
     * Floor each share to a whole credit; the rounding remainder is
     * handled by [drawLottery] (sent back into the per-guild jackpot).
     */
    internal fun prizeShares(slots: Int, pool: Long): List<Long> {
        if (slots <= 0 || pool <= 0L) return emptyList()
        val pcts: DoubleArray = when (slots) {
            1 -> doubleArrayOf(1.0)
            2 -> doubleArrayOf(0.6, 0.4)
            3 -> doubleArrayOf(0.5, 0.3, 0.2)
            4 -> doubleArrayOf(0.4, 0.3, 0.2, 0.1)
            else -> {
                // Linear taper: weight i = (slots - i); normalise.
                val raw = DoubleArray(slots) { (slots - it).toDouble() }
                val sum = raw.sum().coerceAtLeast(1.0)
                DoubleArray(slots) { raw[it] / sum }
            }
        }
        return pcts.map { max(0L, kotlin.math.floor(pool * it).toLong()) }
    }

    /**
     * Decrement the per-guild jackpot row by [amount]. Mirrors the lock
     * + mutate + upsert idiom in `JackpotService.awardJackpot` so the
     * draw doesn't leak credits and matches the same concurrency
     * contract. Caller must already be inside a @Transactional boundary.
     */
    private fun drainFromPool(guildId: Long, amount: Long): Long {
        if (amount <= 0L) return 0L
        // We don't have a public partial-drain on JackpotService; reset
        // and re-deposit the leftover — a single transaction so the pool
        // never observably gaps. This matches the resetPool→addToPool
        // sequence the moderation flow already uses.
        val pool = jackpotService.resetPool(guildId)
        val drained = amount.coerceAtMost(pool)
        val leftover = pool - drained
        if (leftover > 0L) jackpotService.addToPool(guildId, leftover)
        return drained
    }
}
