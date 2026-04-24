package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.UserDto
import database.economy.TobyCoinEngine
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TobyCoinMarketService
import database.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class EconomyTradeServiceIntegrationTest {

    @Autowired lateinit var tradeService: EconomyTradeService
    @Autowired lateinit var marketService: TobyCoinMarketService
    @Autowired lateinit var userService: UserService

    // Each test calls newFixture() to get a unique (discordId, guildId) pair so
    // the shared Spring context / in-memory H2 can't leak market or user rows
    // between tests.
    private fun newFixture(openingCredits: Long = 1_000L): Fixture {
        val id = seq.incrementAndGet()
        val fx = Fixture(discordId = 900_000L + id, guildId = 900_000L + id)
        userService.clearCache()
        userService.createNewUser(UserDto(fx.discordId, fx.guildId).apply { socialCredit = openingCredits })
        return fx
    }

    private data class Fixture(val discordId: Long, val guildId: Long)

    companion object {
        private val seq = AtomicLong()
    }

    @Test
    fun `loadOrCreateMarket seeds a market at INITIAL_PRICE with an opening history sample`() {
        val fx = newFixture()

        val market = tradeService.loadOrCreateMarket(fx.guildId)

        assertEquals(TobyCoinEngine.INITIAL_PRICE, market.price, 1e-9)
        val history = marketService.listAllHistory(fx.guildId)
        assertEquals(1, history.size, "opening sample should be the only history row")
        assertEquals(TobyCoinEngine.INITIAL_PRICE, history.first().price, 1e-9)
    }

    @Test
    fun `loadOrCreateMarket is idempotent on subsequent calls`() {
        val fx = newFixture()

        val first = tradeService.loadOrCreateMarket(fx.guildId)
        val second = tradeService.loadOrCreateMarket(fx.guildId)

        assertEquals(first.price, second.price, 1e-9)
        assertEquals(1, marketService.listAllHistory(fx.guildId).size, "no extra sample on reload")
    }

    @Test
    fun `buy persists new balances and appends a price sample`() {
        val fx = newFixture()
        tradeService.loadOrCreateMarket(fx.guildId)
        val openingPrice = marketService.getMarket(fx.guildId)!!.price
        val openingHistorySize = marketService.listAllHistory(fx.guildId).size

        val outcome = tradeService.buy(fx.discordId, fx.guildId, 5L)

        val ok = assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(5L, user.tobyCoins)
        assertEquals(1_000L - ok.transactedCredits, user.socialCredit)

        val market = marketService.getMarket(fx.guildId)!!
        assertTrue(market.price > openingPrice, "buy should push market price up")

        val samples = marketService.listAllHistory(fx.guildId)
        assertEquals(openingHistorySize + 1, samples.size, "one sample should be appended")
        assertEquals(market.price, samples.last().price, 1e-9)
    }

    @Test
    fun `buy with insufficient credit leaves balances, market price and history untouched`() {
        val fx = newFixture(openingCredits = 10L)
        tradeService.loadOrCreateMarket(fx.guildId)
        val openingPrice = marketService.getMarket(fx.guildId)!!.price
        val openingHistorySize = marketService.listAllHistory(fx.guildId).size

        val outcome = tradeService.buy(fx.discordId, fx.guildId, 100L)

        assertInstanceOf(TradeOutcome.InsufficientCredits::class.java, outcome)
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(10L, user.socialCredit)
        assertEquals(0L, user.tobyCoins)
        assertEquals(openingPrice, marketService.getMarket(fx.guildId)!!.price, 1e-9)
        assertEquals(openingHistorySize, marketService.listAllHistory(fx.guildId).size)
    }

    @Test
    fun `sell returns credits, decrements coins, and lowers the market price`() {
        val fx = newFixture()
        tradeService.loadOrCreateMarket(fx.guildId)
        val bought = assertInstanceOf(
            TradeOutcome.Ok::class.java,
            tradeService.buy(fx.discordId, fx.guildId, 5L)
        )

        val sold = assertInstanceOf(
            TradeOutcome.Ok::class.java,
            tradeService.sell(fx.discordId, fx.guildId, 5L)
        )

        assertEquals(0L, sold.newCoins)
        assertTrue(sold.newPrice < bought.newPrice, "sell should drop below the post-buy price")
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(0L, user.tobyCoins)
    }

    @Test
    fun `sell with insufficient coins is rejected`() {
        val fx = newFixture()
        tradeService.loadOrCreateMarket(fx.guildId)

        val outcome = tradeService.sell(fx.discordId, fx.guildId, 3L)

        assertInstanceOf(TradeOutcome.InsufficientCoins::class.java, outcome)
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(0L, user.tobyCoins)
        assertEquals(1_000L, user.socialCredit)
    }

    @Test
    fun `unknown user is rejected and no market is created`() {
        val ghostGuild = 900_000L + seq.incrementAndGet()

        val outcome = tradeService.buy(1L, ghostGuild, 1L)

        assertInstanceOf(TradeOutcome.UnknownUser::class.java, outcome)
        assertEquals(null, marketService.getMarket(ghostGuild))
        assertTrue(marketService.listAllHistory(ghostGuild).isEmpty())
    }

    @Test
    fun `invalid amount is rejected for both buy and sell`() {
        val fx = newFixture()

        assertInstanceOf(TradeOutcome.InvalidAmount::class.java, tradeService.buy(fx.discordId, fx.guildId, 0L))
        assertInstanceOf(TradeOutcome.InvalidAmount::class.java, tradeService.sell(fx.discordId, fx.guildId, -5L))
    }
}
