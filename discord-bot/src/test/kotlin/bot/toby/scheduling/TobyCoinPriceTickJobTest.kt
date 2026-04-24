package bot.toby.scheduling

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.service.TobyCoinMarketService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Regression guard for Issue 7: the old code reseeded `Random` each tick
 * with `epochMillis xor guildId`, producing predictable/biased outputs.
 * The job now uses a single long-lived `Random`, so consecutive ticks
 * starting from the same price produce (overwhelmingly) different outputs.
 */
class TobyCoinPriceTickJobTest {

    @Test
    fun `consecutive ticks produce different prices with very high probability`() {
        val jda: JDA = mockk(relaxed = true)
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        val guild: Guild = mockk(relaxed = true)
        every { guild.idLong } returns 1L

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { cache.iterator() } answers { mutableListOf(guild).iterator() }
        every { jda.guildCache } returns cache

        var currentPrice = 100.0
        every { marketService.getMarket(1L) } answers {
            TobyCoinMarketDto(guildId = 1L, price = currentPrice, lastTickAt = Instant.now())
        }
        val saved = slot<TobyCoinMarketDto>()
        every { marketService.saveMarket(capture(saved)) } answers {
            currentPrice = saved.captured.price
            saved.captured
        }
        every { marketService.appendPricePoint(any()) } answers { firstArg<TobyCoinPricePointDto>() }
        every { marketService.pruneHistoryOlderThan(any()) } returns 0

        val job = TobyCoinPriceTickJob(jda, marketService)

        val prices = (1..50).map {
            job.tickAllGuilds()
            currentPrice
        }

        // With a real PRNG every tick draws an independent Gaussian — two
        // consecutive ticks landing on identical doubles is astronomically
        // unlikely. The pre-fix job (reseeded from `nowMs xor guildId` every
        // tick) could produce near-duplicate outputs for same-ms reseeds.
        val duplicates = prices.zipWithNext().count { (a, b) -> a == b }
        assertTrue(duplicates == 0, "expected every tick to produce a distinct price but saw $duplicates duplicates")
        assertEquals(50, prices.toSet().size, "all 50 ticks should have distinct prices")
    }
}
