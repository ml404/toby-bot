package bot.toby.scheduling

import common.logging.DiscordLogger
import database.service.lottery.JackpotLotteryService
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Refreshes the open-lottery announcement embed every five minutes so
 * the "Today's draw — N credits in the pool" line tracks live ticket
 * sales. Cadence mirrors [TobyCoinPriceTickJob] — cheap because
 * [LotteryAnnouncer.refreshAnnouncement] short-circuits when the pool
 * hasn't grown since the last edit, so quiet guilds make zero Discord
 * round-trips.
 *
 * Both NUMBER_MATCH (daily auto-draw) and TICKET_WEIGHTED
 * (admin-fired) open lotteries are refreshed — admins running an
 * ad-hoc weighted draw also benefit from the live pool growth.
 *
 * Per-guild error isolation via `runCatching`. Prod-profile only,
 * matching [LotteryDailyJob] / [TobyCoinPriceTickJob] — dev guilds
 * don't need a 5-minute Discord edit.
 */
@Component
@Profile("prod")
class LotteryRefreshJob @Autowired constructor(
    private val jda: JDA,
    private val jackpotLotteryService: JackpotLotteryService,
    private val lotteryAnnouncer: LotteryAnnouncer,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    fun refreshAll() {
        jda.guildCache.forEach { guild ->
            runCatching {
                jackpotLotteryService.getOpenLotteriesForRefresh(guild.idLong).forEach { lottery ->
                    lotteryAnnouncer.refreshAnnouncement(guild, lottery)
                }
            }.onFailure {
                logger.warn("Lottery refresh failed for guild ${guild.idLong}: ${it.message}")
            }
        }
    }
}
