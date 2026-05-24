package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.lottery.JackpotLotteryDto
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import database.service.lottery.LotteryDailyService
import database.service.lottery.LotteryHelper
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily lottery auto-draw. Runs at 00:00 UTC.
 *
 * For each guild with `LOTTERY_DAILY_ENABLED=true`, dispatches on
 * `LOTTERY_DAILY_MODE`:
 *
 *   - **NUMBER_MATCH** (default): Pick 5 of 1-49.
 *   - **WEIGHTED**: top-3 weighted draw, 50/30/20 % of pool.
 *
 * Both modes share the same outer flow: close yesterday's draw (or
 * cancel + refund if there were no tickets, or distinct buyers were
 * below the [LotteryHelper.dailyMinBuyers] threshold), then open a
 * fresh one seeded from the jackpot pool. After the cycle, hand the
 * outcome to [LotteryAnnouncer] so winners get pinged in the configured
 * announce channel.
 *
 * Idempotent via composite-key ledger ([LotteryDailyService]) — a
 * mid-cron restart skips guilds whose ledger already records today.
 * Per-guild error isolation via `runCatching`. Prod-profile only.
 */
@Component
@Profile("prod")
class LotteryDailyJob @Autowired constructor(
    private val jda: JDA,
    private val configService: ConfigService,
    private val lotteryDailyService: LotteryDailyService,
    private val jackpotLotteryService: JackpotLotteryService,
    private val lotteryAnnouncer: LotteryAnnouncer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    fun runDaily() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        logger.info { "Running daily lottery job for $today" }

        jda.guildCache.forEach { guild ->
            runCatching { rollGuild(guild, today) }
                .onFailure {
                    logger.error("Daily lottery roll failed for guild ${guild.idLong}: ${it.message}")
                }
        }
    }

    /**
     * Roll the daily draw for a single guild. Public for the moderation
     * tab's "Force draw now" button — both call sites share the same
     * close→open→announce→mark-ran flow.
     */
    fun rollGuild(guild: Guild, today: LocalDate) {
        val guildId = guild.idLong
        if (!LotteryHelper.dailyEnabled(configService, guildId)) {
            logger.info { "Daily lottery disabled for guild $guildId; skipping." }
            return
        }
        if (lotteryDailyService.alreadyRan(guildId, today)) {
            logger.info { "Daily lottery already ran for guild $guildId on $today; skipping." }
            return
        }

        val mode = LotteryHelper.dailyMode(configService, guildId)
        val result = when (mode) {
            LotteryHelper.MODE_WEIGHTED -> cycleWeighted(guildId)
            else -> cycleNumberMatch(guildId)  // MODE_NUMBER_MATCH (default)
        }

        // Always announce — even Skipped opens get a "no draw today"
        // message so the channel reflects what actually happened.
        lotteryAnnouncer.announceCycle(guild, mode, result.prior, result.open)

        if (result.markRan) {
            lotteryDailyService.markRan(guildId, today)
        }
        // If markRan is false the open call returned InvalidParams —
        // don't mark ran so the next cron tick retries once admin
        // fixes the offending config.
    }

    /**
     * NUMBER_MATCH cycle: close (or cancel) yesterday's match lottery,
     * open a fresh one. Returns the prior + open outcomes so the
     * announcer can render a combined message.
     */
    private fun cycleNumberMatch(guildId: Long): CycleResult {
        val prior = closePriorMatch(guildId)
        val (open, markRan) = openMatch(guildId)
        return CycleResult(prior, open, markRan)
    }

    private fun closePriorMatch(guildId: Long): LotteryAnnouncer.PriorOutcome? {
        val openMatch = jackpotLotteryService.getOpenMatch(guildId)
        if (openMatch?.status != JackpotLotteryDto.STATUS_OPEN) return null
        return when (val drawResult = jackpotLotteryService.drawMatchLottery(guildId)) {
            is JackpotLotteryService.DrawMatchOutcome.Ok -> {
                logger.info {
                    "Daily NUMBER_MATCH drew for guild $guildId: drawn=${drawResult.drawnNumbers} " +
                        "totalPaid=${drawResult.totalPaid} rolledBack=${drawResult.rolledBackToJackpot}"
                }
                LotteryAnnouncer.PriorOutcome.MatchDrawn(
                    drawnNumbers = drawResult.drawnNumbers,
                    tierPayouts = drawResult.tierPayouts,
                    totalPaid = drawResult.totalPaid,
                    rolledBack = drawResult.rolledBackToJackpot,
                )
            }
            JackpotLotteryService.DrawMatchOutcome.NoTickets -> {
                logger.info { "Daily NUMBER_MATCH for guild $guildId had no tickets; cancelling and refunding seed." }
                jackpotLotteryService.cancelMatchLottery(guildId)
                LotteryAnnouncer.PriorOutcome.NoTickets
            }
            is JackpotLotteryService.DrawMatchOutcome.BelowMinBuyers -> {
                logger.info {
                    "Daily NUMBER_MATCH for guild $guildId below buyer threshold " +
                        "(${drawResult.have}/${drawResult.need}); cancelling and refunding."
                }
                jackpotLotteryService.cancelMatchLottery(guildId)
                LotteryAnnouncer.PriorOutcome.BelowMinBuyers(drawResult.have, drawResult.need)
            }
            JackpotLotteryService.DrawMatchOutcome.NoOpenLottery -> null
        }
    }

    private fun openMatch(guildId: Long): Pair<LotteryAnnouncer.OpenSummary, Boolean> {
        val ticketPrice = LotteryHelper.dailyTicketPrice(configService, guildId)
        val seedPct = LotteryHelper.dailySeedPct(configService, guildId)
        return when (val open = jackpotLotteryService.openMatchLottery(
            guildId = guildId, ticketPrice = ticketPrice,
            seedPct = seedPct, durationHours = DURATION_HOURS,
        )) {
            is JackpotLotteryService.OpenOutcome.Ok -> {
                logger.info {
                    "Daily NUMBER_MATCH opened for guild $guildId: ticketPrice=$ticketPrice " +
                        "seeded=${open.seeded} seedPct=$seedPct"
                }
                LotteryAnnouncer.OpenSummary.Ok(
                    lotteryId = open.lottery.id,
                    seeded = open.seeded,
                    ticketPrice = ticketPrice,
                    poolAmount = open.lottery.poolAmount,
                ) to true
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId is already open; skipping reopen." }
                LotteryAnnouncer.OpenSummary.Skipped to true
            }
            JackpotLotteryService.OpenOutcome.EmptyPool -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId reported EmptyPool unexpectedly." }
                LotteryAnnouncer.OpenSummary.Skipped to true
            }
            is JackpotLotteryService.OpenOutcome.InvalidParams -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId rejected params: ${open.reason}" }
                LotteryAnnouncer.OpenSummary.Skipped to false
            }
        }
    }

    /**
     * WEIGHTED cycle: close (or cancel) yesterday's weighted lottery,
     * open a fresh one. Top 3 of N tickets share the pool 50/30/20.
     */
    private fun cycleWeighted(guildId: Long): CycleResult {
        val prior = closePriorWeighted(guildId)
        val (open, markRan) = openWeighted(guildId)
        return CycleResult(prior, open, markRan)
    }

    private fun closePriorWeighted(guildId: Long): LotteryAnnouncer.PriorOutcome? {
        val openWeighted = jackpotLotteryService.getOpenWeighted(guildId)
        if (openWeighted?.status != JackpotLotteryDto.STATUS_OPEN) return null
        return when (val drawResult = jackpotLotteryService.drawLottery(guildId)) {
            is JackpotLotteryService.DrawOutcome.Ok -> {
                logger.info {
                    "Daily WEIGHTED drew for guild $guildId: " +
                        "totalPaid=${drawResult.totalPaid} drained=${drawResult.drained}"
                }
                LotteryAnnouncer.PriorOutcome.WeightedDrawn(
                    payouts = drawResult.payouts,
                    totalPaid = drawResult.totalPaid,
                    drained = drawResult.drained,
                    bonusTicketsAwarded = drawResult.bonusTicketsAwarded,
                    highestMilestoneFired = drawResult.highestMilestoneFired,
                )
            }
            JackpotLotteryService.DrawOutcome.NoTickets -> {
                logger.info { "Daily WEIGHTED for guild $guildId had no tickets; cancelling and refunding seed." }
                jackpotLotteryService.cancelLottery(guildId)
                LotteryAnnouncer.PriorOutcome.NoTickets
            }
            is JackpotLotteryService.DrawOutcome.BelowMinBuyers -> {
                logger.info {
                    "Daily WEIGHTED for guild $guildId below buyer threshold " +
                        "(${drawResult.have}/${drawResult.need}); cancelling and refunding."
                }
                jackpotLotteryService.cancelLottery(guildId)
                LotteryAnnouncer.PriorOutcome.BelowMinBuyers(drawResult.have, drawResult.need)
            }
            JackpotLotteryService.DrawOutcome.NoOpenLottery -> null
        }
    }

    private fun openWeighted(guildId: Long): Pair<LotteryAnnouncer.OpenSummary, Boolean> {
        val ticketPrice = LotteryHelper.dailyTicketPrice(configService, guildId)
        val seedPct = LotteryHelper.dailySeedPct(configService, guildId)
        val drainPct = (seedPct.toDouble() / 100.0).coerceIn(0.0, 1.0)
        return when (val open = jackpotLotteryService.openLottery(
            guildId = guildId,
            ticketPrice = ticketPrice,
            durationHours = DURATION_HOURS,
            winnerCount = LotteryHelper.WEIGHTED_DAILY_WINNER_COUNT,
            drainPct = drainPct,
        )) {
            is JackpotLotteryService.OpenOutcome.Ok -> {
                logger.info {
                    "Daily WEIGHTED opened for guild $guildId: ticketPrice=$ticketPrice " +
                        "seeded=${open.seeded} seedPct=$seedPct winners=${LotteryHelper.WEIGHTED_DAILY_WINNER_COUNT}"
                }
                LotteryAnnouncer.OpenSummary.Ok(
                    lotteryId = open.lottery.id,
                    seeded = open.seeded,
                    ticketPrice = ticketPrice,
                    poolAmount = open.lottery.poolAmount,
                ) to true
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen -> {
                logger.warn { "Daily WEIGHTED for guild $guildId is already open; skipping reopen." }
                LotteryAnnouncer.OpenSummary.Skipped to true
            }
            JackpotLotteryService.OpenOutcome.EmptyPool -> {
                logger.info {
                    "Daily WEIGHTED for guild $guildId skipped — jackpot empty. " +
                        "Admin must seed the pool before the next cycle can open."
                }
                // Mark as ran anyway so we don't spam logs every minute;
                // admin can use the moderation tab's force-draw button
                // for an immediate retry once they fund the pool.
                LotteryAnnouncer.OpenSummary.Skipped to true
            }
            is JackpotLotteryService.OpenOutcome.InvalidParams -> {
                logger.warn { "Daily WEIGHTED for guild $guildId rejected params: ${open.reason}" }
                LotteryAnnouncer.OpenSummary.Skipped to false
            }
        }
    }

    private data class CycleResult(
        val prior: LotteryAnnouncer.PriorOutcome?,
        val open: LotteryAnnouncer.OpenSummary,
        val markRan: Boolean,
    )

    companion object {
        /** A day. The daily draw opens for 24h and closes at the next tick. */
        private const val DURATION_HOURS: Long = 24L
    }
}
