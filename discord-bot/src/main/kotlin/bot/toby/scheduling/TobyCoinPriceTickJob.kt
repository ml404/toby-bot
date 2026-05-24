package bot.toby.scheduling

import bot.toby.notify.NotificationRouter
import bot.toby.notify.PriceAlertReceiptBuilder
import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.UserPriceTriggerDto
import common.economy.TobyCoinEngine
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserPriceTriggerService
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Ticks the Toby Coin price for every known guild on a 5-minute cadence.
 *
 * Each tick applies a GBM random-walk step (so the chart keeps moving even
 * when nobody trades) and appends a sample to the price history so the
 * chart has fresh data. Old history rows are pruned after each pass.
 *
 * After persisting the new price, [handleTriggered] scans
 * `user_price_trigger` rows whose target was reached by the tick and
 * auto-executes the user's declared BUY/SELL via [EconomyTradeService],
 * DM'ing a receipt via [NotificationRouter]. Triggers are one-shot —
 * fired rows are disabled so an oscillation around the threshold can't
 * replay the trade.
 */
@Component
@Profile("prod")
class TobyCoinPriceTickJob @Autowired constructor(
    private val jda: JDA,
    private val marketService: TobyCoinMarketService,
    private val triggerService: UserPriceTriggerService,
    private val tradeService: EconomyTradeService,
    private val notificationRouter: NotificationRouter,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    // Single long-lived PRNG. Reseeding per tick with predictable seeds
    // (epoch-ms xor guildId) biases consecutive outputs; letting the state
    // evolve naturally gives an honest random walk.
    private val random: Random = Random.Default

    companion object {
        // Kept long enough that the /economy 1Y view always has real data
        // to draw, and that the Recent trades list mirrors the same window.
        // Pruning still protects the table from growing forever on long-
        // lived guilds.
        val HISTORY_RETENTION: Duration = Duration.ofDays(400)
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun tickAllGuilds() {
        val now = Instant.now()
        jda.guildCache.forEach { guild ->
            runCatching { tickGuild(guild.idLong, now) }
                .onFailure { logger.error("Toby coin tick failed for guild ${guild.idLong}: ${it.message}") }
        }
        val cutoff = now.minus(HISTORY_RETENTION)
        runCatching { marketService.pruneHistoryOlderThan(cutoff) }
            .onFailure { logger.warn("Toby coin history prune failed: ${it.message}") }
        runCatching {
            val removed = marketService.pruneTradesOlderThan(cutoff)
            if (removed > 0) logger.info { "Pruned $removed expired toby coin trade rows." }
        }.onFailure { logger.warn("Toby coin trade prune failed: ${it.message}") }
    }

    private fun tickGuild(guildId: Long, now: Instant) {
        val market = marketService.getMarket(guildId) ?: TobyCoinMarketDto(
            guildId = guildId,
            price = TobyCoinEngine.INITIAL_PRICE,
            lastTickAt = now
        )
        val newPrice = TobyCoinEngine.tickRandomWalk(market.price, random = random)
        market.price = newPrice
        market.lastTickAt = now
        marketService.saveMarket(market)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = guildId, sampledAt = now, price = newPrice)
        )
        handleTriggered(guildId, newPrice, now)
    }

    private fun handleTriggered(guildId: Long, newPrice: Double, now: Instant) {
        val rows = triggerService.findTriggered(guildId, newPrice)
        if (rows.isEmpty()) return
        rows.forEach { row ->
            runCatching { executeTrigger(row, guildId, now) }
                .onFailure { err ->
                    logger.warn(
                        "Price-alert auto-trade failed trigger=${row.id} " +
                                "user=${row.discordId} guild=$guildId: ${err.message}"
                    )
                    // Disable the row anyway so a broken trigger doesn't
                    // re-fire every 5 minutes for the next 24 hours. A
                    // second failure here would otherwise be silent —
                    // log it so the operator sees both halves.
                    runCatching { triggerService.markFired(row.id!!, now) }
                        .onFailure { markErr ->
                            logger.warn(
                                "Also failed to disable broken trigger=${row.id} " +
                                        "after trade-execution failure: ${markErr.message}"
                            )
                        }
                }
        }
    }

    private fun executeTrigger(row: UserPriceTriggerDto, guildId: Long, now: Instant) {
        val side = runCatching { row.sideEnum }.getOrElse {
            logger.warn("Unknown side ${row.side} on trigger ${row.id}; disabling.")
            triggerService.markFired(row.id!!, now)
            return
        }
        val outcome = when (side) {
            UserPriceTriggerDto.Side.BUY -> tradeService.buy(row.discordId, guildId, row.amount)
            UserPriceTriggerDto.Side.SELL -> tradeService.sell(row.discordId, guildId, row.amount)
        }
        notificationRouter.dispatch(NotificationChannelKind.PRICE_ALERT, row.discordId, guildId) {
            dm { PriceAlertReceiptBuilder.buildDm(row, outcome) }
            push { PriceAlertReceiptBuilder.buildPush(row, outcome) }
        }
        triggerService.markFired(row.id!!, now)
    }
}
