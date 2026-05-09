package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryDailyService
import database.service.LotteryHelper
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
 *   - **NUMBER_MATCH** (default): Pick 5 of 1-49. Closes yesterday's
 *     draw via [JackpotLotteryService.drawMatchLottery] (or cancels if
 *     no tickets), opens a fresh one via
 *     [JackpotLotteryService.openMatchLottery]. Best for high-volume
 *     servers — match tiers (5/4/3/2 → 60/25/10/5%) need many tickets
 *     per draw to hit reliably.
 *
 *   - **WEIGHTED**: top-3 weighted draw, 50/30/20 % of pool. Closes
 *     yesterday's draw via [JackpotLotteryService.drawLottery] (or
 *     cancels if no tickets), opens a fresh one via
 *     [JackpotLotteryService.openLottery] with `winnerCount=3`. Best
 *     for low-volume servers — a winner is guaranteed every draw
 *     regardless of ticket count, so the pool drains predictably.
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
     * Roll the daily draw for a single guild. Public/internal for the
     * moderation tab's "Force draw now" button — the web service
     * delegates here so the same close→open→mark-ran flow drives both
     * scheduled and manual triggers.
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
        val opened = when (mode) {
            LotteryHelper.MODE_WEIGHTED -> rollWeighted(guildId)
            else -> rollNumberMatch(guildId)  // MODE_NUMBER_MATCH (default)
        }

        if (opened) {
            lotteryDailyService.markRan(guildId, today)
        }
        // If `opened` is false the open call returned InvalidParams —
        // don't mark ran so the next cron tick retries once admin
        // fixes the offending config.
    }

    /**
     * NUMBER_MATCH cycle: close (or cancel) yesterday's match lottery,
     * open a fresh one. Returns true if the open call succeeded (or
     * AlreadyOpen — race with another worker is treated as success).
     */
    private fun rollNumberMatch(guildId: Long): Boolean {
        val openMatch = jackpotLotteryService.getOpenMatch(guildId)
        if (openMatch?.status == JackpotLotteryDto.STATUS_OPEN) {
            when (val drawResult = jackpotLotteryService.drawMatchLottery(guildId)) {
                is JackpotLotteryService.DrawMatchOutcome.Ok ->
                    logger.info {
                        "Daily NUMBER_MATCH drew for guild $guildId: drawn=${drawResult.drawnNumbers} " +
                            "totalPaid=${drawResult.totalPaid} rolledBack=${drawResult.rolledBackToJackpot}"
                    }
                JackpotLotteryService.DrawMatchOutcome.NoTickets -> {
                    logger.info { "Daily NUMBER_MATCH for guild $guildId had no tickets; cancelling and refunding seed." }
                    jackpotLotteryService.cancelMatchLottery(guildId)
                }
                JackpotLotteryService.DrawMatchOutcome.NoOpenLottery -> Unit
            }
        }

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
                true
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId is already open; skipping reopen." }
                true
            }
            JackpotLotteryService.OpenOutcome.EmptyPool -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId reported EmptyPool unexpectedly." }
                true
            }
            is JackpotLotteryService.OpenOutcome.InvalidParams -> {
                logger.warn { "Daily NUMBER_MATCH for guild $guildId rejected params: ${open.reason}" }
                false
            }
        }
    }

    /**
     * WEIGHTED cycle: close (or cancel) yesterday's weighted lottery,
     * open a fresh one. Top 3 of N tickets share the pool 50/30/20 —
     * always pays out at any ticket volume, so this is the right mode
     * for low-engagement servers.
     */
    private fun rollWeighted(guildId: Long): Boolean {
        val openWeighted = jackpotLotteryService.getOpenWeighted(guildId)
        if (openWeighted?.status == JackpotLotteryDto.STATUS_OPEN) {
            when (val drawResult = jackpotLotteryService.drawLottery(guildId)) {
                is JackpotLotteryService.DrawOutcome.Ok ->
                    logger.info {
                        "Daily WEIGHTED drew for guild $guildId: " +
                            "totalPaid=${drawResult.totalPaid} drained=${drawResult.drained}"
                    }
                JackpotLotteryService.DrawOutcome.NoTickets -> {
                    logger.info { "Daily WEIGHTED for guild $guildId had no tickets; cancelling and refunding seed." }
                    jackpotLotteryService.cancelLottery(guildId)
                }
                JackpotLotteryService.DrawOutcome.NoOpenLottery -> Unit
            }
        }

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
                true
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen -> {
                logger.warn { "Daily WEIGHTED for guild $guildId is already open; skipping reopen." }
                true
            }
            JackpotLotteryService.OpenOutcome.EmptyPool -> {
                logger.info {
                    "Daily WEIGHTED for guild $guildId skipped — jackpot empty. " +
                        "Admin must seed the pool before the next cycle can open."
                }
                // Mark as ran anyway so we don't spam the log; tomorrow
                // will retry. If the admin wants an immediate retry they
                // can use the moderation tab's force-draw button.
                true
            }
            is JackpotLotteryService.OpenOutcome.InvalidParams -> {
                logger.warn { "Daily WEIGHTED for guild $guildId rejected params: ${open.reason}" }
                false
            }
        }
    }

    companion object {
        /** A day. The daily draw opens for 24h and closes at the next tick. */
        private const val DURATION_HOURS: Long = 24L
    }
}
