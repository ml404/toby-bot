package web.service

import common.logging.DiscordLogger
import database.service.CasinoAdminService
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.JackpotService
import database.service.LotteryHelper
import org.springframework.stereotype.Service

/**
 * Admin-only jackpot/lottery operations carved out of
 * [ModerationWebService]. These methods all share the same access
 * gate ([ModerationAuthorizer.canModerate]) and the same back-end
 * dependencies ([JackpotService], [CasinoAdminService],
 * [JackpotLotteryService], [ConfigService]) — keeping them together
 * makes the dependency graph here narrow and lets the rest of
 * ModerationWebService stop carrying jackpot wiring.
 *
 * ModerationWebService retains delegating shims pointing here so
 * existing callers don't change; the goal of this PR is a clean
 * extraction, not a rename of the public API.
 */
@Service
class CasinoAuditService(
    private val configService: ConfigService,
    private val jackpotService: JackpotService,
    private val casinoAdminService: CasinoAdminService,
    private val jackpotLotteryService: JackpotLotteryService,
    private val authorizer: ModerationAuthorizer,
) {
    companion object {
        private val logger = DiscordLogger(CasinoAuditService::class.java)
        const val NOT_ALLOWED = "You are not allowed to moderate this server."
    }

    /** Current per-guild jackpot pool size; for the admin tab banner. */
    fun getJackpotPool(guildId: Long): Long = jackpotService.getPool(guildId)

    /**
     * Reset (zero) the per-guild jackpot pool. Returns null on success
     * with `drained` populated; non-null on permission failure.
     */
    data class ResetJackpotResult(val error: String?, val drained: Long, val newPool: Long)

    fun resetJackpotPool(actorDiscordId: Long, guildId: Long): ResetJackpotResult {
        if (!authorizer.canModerate(actorDiscordId, guildId)) {
            return ResetJackpotResult(NOT_ALLOWED, 0L, jackpotService.getPool(guildId))
        }
        val drained = casinoAdminService.resetJackpot(guildId)
        logger.info("Casino admin reset jackpot for guild=$guildId by actor=$actorDiscordId, drained=$drained")
        return ResetJackpotResult(null, drained, 0L)
    }

    /**
     * Debit `amount` from `sourceDiscordId` and deposit it into the
     * per-guild jackpot pool. Used to claw back exploit gains and
     * return them to the pool that funded them.
     */
    data class RefundJackpotResult(
        val error: String?,
        val drained: Long,
        val newPool: Long,
        val newSourceBalance: Long,
    )

    fun refundJackpotFromUser(
        actorDiscordId: Long,
        guildId: Long,
        sourceDiscordId: Long,
        amount: Long,
    ): RefundJackpotResult {
        if (!authorizer.canModerate(actorDiscordId, guildId)) {
            return RefundJackpotResult(NOT_ALLOWED, 0L, jackpotService.getPool(guildId), 0L)
        }
        if (amount <= 0L) {
            return RefundJackpotResult("Amount must be positive.", 0L, jackpotService.getPool(guildId), 0L)
        }
        return when (val outcome = casinoAdminService.refundToJackpot(sourceDiscordId, guildId, amount)) {
            is CasinoAdminService.RefundOutcome.Ok -> {
                logger.info(
                    "Casino admin refund-to-jackpot guild=$guildId actor=$actorDiscordId " +
                        "source=$sourceDiscordId amount=${outcome.drained} newPool=${outcome.newPool}"
                )
                RefundJackpotResult(null, outcome.drained, outcome.newPool, outcome.newSourceBalance)
            }
            is CasinoAdminService.RefundOutcome.Insufficient ->
                RefundJackpotResult(
                    "Source user has only ${outcome.have} credits, can't refund ${outcome.needed}.",
                    0L, jackpotService.getPool(guildId), outcome.have,
                )
            is CasinoAdminService.RefundOutcome.InvalidAmount ->
                RefundJackpotResult("Amount must be positive.", 0L, jackpotService.getPool(guildId), 0L)
        }
    }

    /**
     * Force-draw the daily match-numbers lottery for `guildId`: closes
     * the current open draw (paying tier-based prizes) and opens a
     * fresh one seeded from the jackpot. Mirrors what
     * [bot.toby.scheduling.LotteryDailyJob] does at 00:00 UTC, but
     * admin-triggered for testing or when the cron missed (bot was
     * down at midnight, etc).
     */
    data class ForceDrawLotteryResult(
        val error: String?,
        val drewPrior: Boolean,
        val priorTotalPaid: Long,
        val priorRolledBack: Long,
        val priorDrawn: List<Int>,
        val priorBelowMinBuyers: Boolean = false,
        val priorBuyersHave: Int = 0,
        val priorBuyersNeed: Int = 0,
        val openedNew: Boolean,
        val newSeeded: Long,
    )

    fun forceDailyDraw(actorDiscordId: Long, guildId: Long): ForceDrawLotteryResult {
        if (!authorizer.canModerate(actorDiscordId, guildId)) {
            return ForceDrawLotteryResult(
                error = NOT_ALLOWED,
                drewPrior = false, priorTotalPaid = 0L, priorRolledBack = 0L,
                priorDrawn = emptyList(), openedNew = false, newSeeded = 0L,
            )
        }
        val mode = LotteryHelper.dailyMode(configService, guildId)
        // Step 1: close open daily if present (mode-dispatched).
        // Note: this admin-only path doesn't post the channel announcement
        // (the announcer lives in the discord-bot module which the web
        // module doesn't depend on; see LotteryDailyJob for the announce
        // wiring). Force-draw is admin debug / recovery; the response
        // payload + toast are sufficient feedback.
        var drewPrior = false
        var priorTotalPaid = 0L
        var priorRolledBack = 0L
        var priorDrawn: List<Int> = emptyList()
        var priorBelowMinBuyers = false
        var priorBuyersHave = 0
        var priorBuyersNeed = 0
        if (mode == LotteryHelper.MODE_WEIGHTED) {
            when (val drawResult = jackpotLotteryService.drawLottery(guildId)) {
                is JackpotLotteryService.DrawOutcome.Ok -> {
                    drewPrior = true
                    priorTotalPaid = drawResult.totalPaid
                    priorRolledBack = (drawResult.drained - drawResult.totalPaid).coerceAtLeast(0L)
                }
                JackpotLotteryService.DrawOutcome.NoTickets -> {
                    jackpotLotteryService.cancelLottery(guildId)
                    drewPrior = true
                }
                is JackpotLotteryService.DrawOutcome.BelowMinBuyers -> {
                    jackpotLotteryService.cancelLottery(guildId)
                    drewPrior = true
                    priorBelowMinBuyers = true
                    priorBuyersHave = drawResult.have
                    priorBuyersNeed = drawResult.need
                }
                JackpotLotteryService.DrawOutcome.NoOpenLottery -> Unit
            }
        } else {
            when (val drawResult = jackpotLotteryService.drawMatchLottery(guildId)) {
                is JackpotLotteryService.DrawMatchOutcome.Ok -> {
                    drewPrior = true
                    priorTotalPaid = drawResult.totalPaid
                    priorRolledBack = drawResult.rolledBackToJackpot
                    priorDrawn = drawResult.drawnNumbers
                }
                JackpotLotteryService.DrawMatchOutcome.NoTickets -> {
                    jackpotLotteryService.cancelMatchLottery(guildId)
                    drewPrior = true
                }
                is JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers -> {
                    jackpotLotteryService.cancelMatchLottery(guildId)
                    drewPrior = true
                    priorBelowMinBuyers = true
                    priorBuyersHave = drawResult.have
                    priorBuyersNeed = drawResult.need
                }
                JackpotLotteryService.DrawMatchOutcome.NoOpenLottery -> Unit
            }
        }
        // Step 2: open fresh daily (mode-dispatched).
        val ticketPrice = LotteryHelper.dailyTicketPrice(configService, guildId)
        val seedPct = LotteryHelper.dailySeedPct(configService, guildId)
        val open = if (mode == LotteryHelper.MODE_WEIGHTED) {
            jackpotLotteryService.openLottery(
                guildId = guildId,
                ticketPrice = ticketPrice,
                durationHours = 24L,
                winnerCount = LotteryHelper.WEIGHTED_DAILY_WINNER_COUNT,
                drainPct = (seedPct.toDouble() / 100.0).coerceIn(0.0, 1.0),
            )
        } else {
            jackpotLotteryService.openMatchLottery(
                guildId = guildId,
                ticketPrice = ticketPrice,
                seedPct = seedPct,
                durationHours = 24L,
            )
        }
        return when (open) {
            is JackpotLotteryService.OpenOutcome.Ok -> {
                logger.info(
                    "Casino admin force-drew daily lottery: guild=$guildId actor=$actorDiscordId " +
                        "drewPrior=$drewPrior priorTotalPaid=$priorTotalPaid newSeeded=${open.seeded}"
                )
                ForceDrawLotteryResult(
                    error = null,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = true, newSeeded = open.seeded,
                )
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen ->
                ForceDrawLotteryResult(
                    error = "A daily lottery is already open — close it first.",
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
            JackpotLotteryService.OpenOutcome.EmptyPool ->
                ForceDrawLotteryResult(
                    error = null,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
            is JackpotLotteryService.OpenOutcome.InvalidParams ->
                ForceDrawLotteryResult(
                    error = open.reason,
                    drewPrior = drewPrior, priorTotalPaid = priorTotalPaid,
                    priorRolledBack = priorRolledBack, priorDrawn = priorDrawn,
                    priorBelowMinBuyers = priorBelowMinBuyers,
                    priorBuyersHave = priorBuyersHave,
                    priorBuyersNeed = priorBuyersNeed,
                    openedNew = false, newSeeded = 0L,
                )
        }
    }
}
