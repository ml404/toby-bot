package bot.toby.scheduling

import bot.toby.notify.NotificationDispatch
import bot.toby.notify.NotificationRouter
import common.notification.NotificationChannelKind
import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.UserPriceTriggerDto
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TobyCoinMarketService
import database.service.UserPriceTriggerService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Exercises the auto-trade wiring inside [TobyCoinPriceTickJob.tickGuild]:
 * a tick that crosses a trigger's target must call the trade service
 * with the trigger's side+amount, dispatch a PRICE_ALERT notification
 * with BOTH `dm{}` and `push{}` wired (NotificationRouter enforces
 * this), and mark the row fired.
 */
class TobyCoinPriceTickJobAutoTradeTest {

    private val guildId = 7L
    private val discordId = 99L

    private fun newJob(
        marketService: TobyCoinMarketService,
        triggerService: UserPriceTriggerService,
        tradeService: EconomyTradeService,
        router: NotificationRouter,
    ): TobyCoinPriceTickJob {
        val jda: JDA = mockk(relaxed = true)
        val guild: Guild = mockk(relaxed = true)
        every { guild.idLong } returns guildId
        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { cache.iterator() } answers { mutableListOf(guild).iterator() }
        every { jda.guildCache } returns cache
        return TobyCoinPriceTickJob(jda, marketService, triggerService, tradeService, router)
    }

    private fun armedTrigger(side: UserPriceTriggerDto.Side = UserPriceTriggerDto.Side.BUY) =
        UserPriceTriggerDto(
            id = 1L,
            discordId = discordId,
            guildId = guildId,
            thresholdPrice = 100.0,
            priceAtCreation = 120.0,
            side = side.name,
            amount = 10L,
            enabled = true,
        )

    @Test
    fun `triggered BUY routes through EconomyTradeService and dispatches receipt`() {
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        val triggerService: UserPriceTriggerService = mockk(relaxed = true)
        val tradeService: EconomyTradeService = mockk(relaxed = true)
        val router: NotificationRouter = mockk(relaxed = true)

        every { marketService.getMarket(guildId) } returns
                TobyCoinMarketDto(guildId = guildId, price = 110.0, lastTickAt = Instant.now())
        every { marketService.saveMarket(any()) } answers { firstArg() }
        every { marketService.appendPricePoint(any()) } answers { firstArg<TobyCoinPricePointDto>() }
        every { marketService.pruneHistoryOlderThan(any()) } returns 0
        every { marketService.pruneTradesOlderThan(any()) } returns 0

        // findTriggered runs after the random walk; force it to return
        // our row regardless of the actual new price so the test isn't
        // flaky on the GBM draw.
        val trigger = armedTrigger()
        every { triggerService.findTriggered(guildId, any()) } returns listOf(trigger)
        every { tradeService.buy(discordId, guildId, 10L) } returns TradeOutcome.Ok(
            amount = 10L,
            transactedCredits = 1010L,
            newCoins = 10L,
            newCredits = 8990L,
            newPrice = 100.5,
            fee = 10L,
        )
        every { triggerService.markFired(1L, any()) } just Runs

        // Capture the dispatch DSL so we can assert both surfaces were wired.
        val configure = slot<NotificationDispatch.() -> Unit>()
        every {
            router.dispatch(NotificationChannelKind.PRICE_ALERT, discordId, guildId, capture(configure))
        } just Runs

        newJob(marketService, triggerService, tradeService, router).tickAllGuilds()

        verify(exactly = 1) { tradeService.buy(discordId, guildId, 10L) }
        verify(exactly = 1) { triggerService.markFired(1L, any()) }
        verify(exactly = 1) {
            router.dispatch(NotificationChannelKind.PRICE_ALERT, discordId, guildId, any())
        }

        // Replay the configure lambda onto a real NotificationDispatch
        // so we can read the internal builder fields. PRICE_ALERT
        // declares DM+PUSH; both must be wired or production dispatch
        // throws via enforceAllSupportedSurfacesWired.
        val plan = NotificationDispatch(NotificationChannelKind.PRICE_ALERT)
        configure.captured.invoke(plan)
        assert(plan.dmBuilder != null) { "PRICE_ALERT dispatch must wire dm{}" }
        assert(plan.pushBuilder != null) { "PRICE_ALERT dispatch must wire push{}" }
    }

    @Test
    fun `triggered SELL calls sell with declared amount`() {
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        val triggerService: UserPriceTriggerService = mockk(relaxed = true)
        val tradeService: EconomyTradeService = mockk(relaxed = true)
        val router: NotificationRouter = mockk(relaxed = true)

        every { marketService.getMarket(guildId) } returns
                TobyCoinMarketDto(guildId = guildId, price = 140.0, lastTickAt = Instant.now())
        every { marketService.saveMarket(any()) } answers { firstArg() }
        every { marketService.appendPricePoint(any()) } answers { firstArg<TobyCoinPricePointDto>() }
        every { marketService.pruneHistoryOlderThan(any()) } returns 0
        every { marketService.pruneTradesOlderThan(any()) } returns 0

        val sell = armedTrigger(UserPriceTriggerDto.Side.SELL)
        every { triggerService.findTriggered(guildId, any()) } returns listOf(sell)
        every { tradeService.sell(discordId, guildId, 10L) } returns TradeOutcome.Ok(
            amount = 10L, transactedCredits = 990L, newCoins = 0L,
            newCredits = 11990L, newPrice = 99.5, fee = 10L,
        )
        every { triggerService.markFired(1L, any()) } just Runs
        every {
            router.dispatch(NotificationChannelKind.PRICE_ALERT, discordId, guildId, any())
        } just Runs

        newJob(marketService, triggerService, tradeService, router).tickAllGuilds()

        verify(exactly = 1) { tradeService.sell(discordId, guildId, 10L) }
        verify(exactly = 0) { tradeService.buy(any(), any(), any()) }
    }

    @Test
    fun `failed trade still disables the trigger`() {
        val marketService: TobyCoinMarketService = mockk(relaxed = true)
        val triggerService: UserPriceTriggerService = mockk(relaxed = true)
        val tradeService: EconomyTradeService = mockk(relaxed = true)
        val router: NotificationRouter = mockk(relaxed = true)

        every { marketService.getMarket(guildId) } returns
                TobyCoinMarketDto(guildId = guildId, price = 110.0, lastTickAt = Instant.now())
        every { marketService.saveMarket(any()) } answers { firstArg() }
        every { marketService.appendPricePoint(any()) } answers { firstArg<TobyCoinPricePointDto>() }
        every { marketService.pruneHistoryOlderThan(any()) } returns 0
        every { marketService.pruneTradesOlderThan(any()) } returns 0

        every { triggerService.findTriggered(guildId, any()) } returns listOf(armedTrigger())
        every { tradeService.buy(discordId, guildId, 10L) } returns
                TradeOutcome.InsufficientCredits(needed = 1010L, have = 50L)
        every { triggerService.markFired(1L, any()) } just Runs
        every {
            router.dispatch(NotificationChannelKind.PRICE_ALERT, discordId, guildId, any())
        } just Runs

        newJob(marketService, triggerService, tradeService, router).tickAllGuilds()

        verify(exactly = 1) { triggerService.markFired(1L, any()) }
        verify(exactly = 1) {
            router.dispatch(NotificationChannelKind.PRICE_ALERT, discordId, guildId, any())
        }
    }
}
