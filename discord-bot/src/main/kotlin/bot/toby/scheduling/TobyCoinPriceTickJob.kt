package bot.toby.scheduling

import database.economy.TobyCoinEngine
import common.logging.DiscordLogger
import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.service.TobyCoinMarketService
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
 */
@Component
@Profile("prod")
class TobyCoinPriceTickJob @Autowired constructor(
    private val jda: JDA,
    private val marketService: TobyCoinMarketService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    // Single long-lived PRNG. Reseeding per tick with predictable seeds
    // (epoch-ms xor guildId) biases consecutive outputs; letting the state
    // evolve naturally gives an honest random walk.
    private val random: Random = Random.Default

    companion object {
        // Kept long enough that the /economy 1Y view always has real data
        // to draw. Pruning older samples still protects the table from
        // growing forever on long-lived guilds.
        val HISTORY_RETENTION: Duration = Duration.ofDays(400)
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun tickAllGuilds() {
        val now = Instant.now()
        jda.guildCache.forEach { guild ->
            runCatching { tickGuild(guild.idLong, now) }
                .onFailure { logger.error("Toby coin tick failed for guild ${guild.idLong}: ${it.message}") }
        }
        runCatching { marketService.pruneHistoryOlderThan(now.minus(HISTORY_RETENTION)) }
            .onFailure { logger.warn("Toby coin history prune failed: ${it.message}") }
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
    }
}
