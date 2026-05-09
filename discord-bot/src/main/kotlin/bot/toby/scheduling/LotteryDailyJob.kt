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
 * Daily match-numbers (Pick 5 of 1-49) lottery auto-draw.
 *
 * Runs at 00:00 UTC. For each guild with `LOTTERY_DAILY_ENABLED=true`:
 *   1. Close yesterday's draw if still OPEN — runs the prize tier
 *      payouts via [JackpotLotteryService.drawMatchLottery]. If no
 *      one bought, cancels and refunds the seed via
 *      [JackpotLotteryService.cancelMatchLottery] so credits don't
 *      strand on a no-tickets row.
 *   2. Open today's draw via [JackpotLotteryService.openMatchLottery],
 *      seeding `LOTTERY_DAILY_SEED_PCT` of the per-guild jackpot pool
 *      (default 5%) into the prize pool. Ticket price comes from
 *      `LOTTERY_DAILY_TICKET_PRICE` (default 50).
 *   3. Mark the day done in [LotteryDailyService] so a mid-cron
 *      restart skips this guild on the next tick.
 *
 * Mirrors [UniversalBasicIncomeJob]: idempotent via composite-key
 * ledger, per-guild error isolation via runCatching, prod-profile only
 * (test profile uses manual force-draw via the moderation web tab).
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

        // Close yesterday's draw if still open.
        val openMatch = jackpotLotteryService.getOpenMatch(guildId)
        if (openMatch?.status == JackpotLotteryDto.STATUS_OPEN) {
            when (val drawResult = jackpotLotteryService.drawMatchLottery(guildId)) {
                is JackpotLotteryService.DrawMatchOutcome.Ok -> {
                    logger.info {
                        "Daily lottery drew for guild $guildId: drawn=${drawResult.drawnNumbers} " +
                            "totalPaid=${drawResult.totalPaid} rolledBack=${drawResult.rolledBackToJackpot}"
                    }
                }
                JackpotLotteryService.DrawMatchOutcome.NoTickets -> {
                    logger.info { "Daily lottery for guild $guildId had no tickets; cancelling and refunding seed." }
                    jackpotLotteryService.cancelMatchLottery(guildId)
                }
                JackpotLotteryService.DrawMatchOutcome.NoOpenLottery -> {
                    // Race with another worker — fine, just continue.
                }
            }
        }

        // Open today's draw.
        val ticketPrice = LotteryHelper.dailyTicketPrice(configService, guildId)
        val seedPct = LotteryHelper.dailySeedPct(configService, guildId)
        when (val open = jackpotLotteryService.openMatchLottery(
            guildId = guildId,
            ticketPrice = ticketPrice,
            seedPct = seedPct,
            durationHours = DURATION_HOURS,
        )) {
            is JackpotLotteryService.OpenOutcome.Ok -> {
                logger.info {
                    "Daily lottery opened for guild $guildId: ticketPrice=$ticketPrice " +
                        "seeded=${open.seeded} seedPct=$seedPct"
                }
            }
            JackpotLotteryService.OpenOutcome.AlreadyOpen -> {
                logger.warn { "Daily lottery for guild $guildId is already open; skipping reopen." }
            }
            JackpotLotteryService.OpenOutcome.EmptyPool -> {
                // Defensive — openMatchLottery accepts empty pools, so this
                // shouldn't fire. Log if it does and move on.
                logger.warn { "Daily lottery for guild $guildId reported EmptyPool unexpectedly." }
            }
            is JackpotLotteryService.OpenOutcome.InvalidParams -> {
                logger.warn { "Daily lottery for guild $guildId rejected params: ${open.reason}" }
                return  // Don't mark as run so admin can fix config and retry.
            }
        }

        lotteryDailyService.markRan(guildId, today)
    }

    companion object {
        /** A day. The daily draw opens for 24h and closes at the next tick. */
        private const val DURATION_HOURS: Long = 24L
    }
}
