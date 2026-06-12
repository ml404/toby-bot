package bot.toby.scheduling

import bot.toby.notify.NotificationRouter
import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinPricePointDto
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserPriceTriggerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The price tick now runs an independent market per coin in the
 * catalogue, for every guild. These guard that the scheduler fans out
 * across all coins rather than only ticking TOBY.
 */
internal class TobyCoinPriceTickJobMultiCoinTest {

    private fun jobOverOneGuild(
        marketService: TobyCoinMarketService,
        triggerService: UserPriceTriggerService,
        tradeService: EconomyTradeService,
    ): TobyCoinPriceTickJob {
        val jda: JDA = mockk(relaxed = true)
        val guild: Guild = mockk(relaxed = true)
        every { guild.idLong } returns 1L
        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { cache.iterator() } answers { mutableListOf(guild).iterator() }
        every { jda.guildCache } returns cache
        return TobyCoinPriceTickJob(jda, marketService, triggerService, tradeService, mockk(relaxed = true))
    }

    @Test
    fun `one pass ticks and persists a market for every coin`() {
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        // Force the fresh-market branch for each coin so the saved row's
        // coin reflects exactly which market was ticked.
        every { marketService.getMarket(any(), any()) } returns null
        val saved = mutableListOf<TobyCoinMarketDto>()
        every { marketService.saveMarket(capture(saved)) } answers { firstArg() }
        val points = mutableListOf<TobyCoinPricePointDto>()
        every { marketService.appendPricePoint(capture(points)) } answers { firstArg() }

        val triggerService: UserPriceTriggerService = mockk(relaxed = true)
        val tradeService: EconomyTradeService = mockk(relaxed = true)

        jobOverOneGuild(marketService, triggerService, tradeService).tickAllGuilds()

        val expected = Coin.entries.map { it.symbol }.toSet()
        assertEquals(expected, saved.map { it.coin }.toSet(), "every coin should be saved")
        assertEquals(expected, points.map { it.coin }.toSet(), "every coin should append a price point")
    }

    @Test
    fun `triggers are scanned once per coin`() {
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        every { marketService.getMarket(any(), any()) } returns null
        every { marketService.saveMarket(any()) } answers { firstArg() }
        every { marketService.appendPricePoint(any()) } answers { firstArg() }

        val triggerService: UserPriceTriggerService = mockk(relaxed = true)
        val tradeService: EconomyTradeService = mockk(relaxed = true)

        jobOverOneGuild(marketService, triggerService, tradeService).tickAllGuilds()

        Coin.entries.forEach { coin ->
            verify(exactly = 1) { triggerService.findTriggered(1L, any(), coin) }
        }
    }
}
