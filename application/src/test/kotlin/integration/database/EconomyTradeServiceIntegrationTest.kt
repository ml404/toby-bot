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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        // Trade ledger: one row recorded with the PRE-pressure price (so the
        // market chart marker shows what the user paid, not the post-trade price).
        val trades = marketService.listTradesSince(fx.guildId, java.time.Instant.EPOCH)
        assertEquals(1, trades.size, "buy should record exactly one trade row")
        val trade = trades.single()
        assertEquals("BUY", trade.side)
        assertEquals(5L, trade.amount)
        assertEquals(fx.discordId, trade.discordId)
        assertEquals(openingPrice, trade.pricePerCoin, 1e-9, "marker must show pre-pressure price")
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

        // Trade ledger: both legs recorded with the right side, in order.
        val trades = marketService.listTradesSince(fx.guildId, java.time.Instant.EPOCH)
        assertEquals(2, trades.size, "buy then sell should record two trade rows")
        assertEquals("BUY", trades[0].side)
        assertEquals("SELL", trades[1].side)
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

    /**
     * Regression guard for the trade TOCTOU race. Before
     * [EconomyTradeService] took a pessimistic row lock, two concurrent
     * buys could both read the same balance and both succeed — double
     * spending the user's credits. The @Transactional + SELECT FOR UPDATE
     * lock forces serialisation so the final numbers reconcile.
     */
    @Test
    fun `concurrent buys cannot double-spend credits`() {
        val threads = 8
        val amount = 1L
        // Price 100, balance = 3 coins worth. Attempting 8 concurrent buys
        // must yield at most 3 successes.
        val fx = newFixture(openingCredits = 300L)
        tradeService.loadOrCreateMarket(fx.guildId)
        val openingPrice = marketService.getMarket(fx.guildId)!!.price

        val executor = Executors.newFixedThreadPool(threads)
        val outcomes = try {
            (1..threads)
                .map { executor.submit<TradeOutcome> { tradeService.buy(fx.discordId, fx.guildId, amount) } }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val successes = outcomes.count { it is TradeOutcome.Ok }
        val failures = outcomes.count { it is TradeOutcome.InsufficientCredits }
        assertEquals(threads, successes + failures, "every outcome must be Ok or InsufficientCredits")
        assertTrue(successes in 1..3, "affordable coins = 3 but saw $successes successes")

        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertTrue(user.socialCredit!! >= 0L, "credits never go negative")
        assertEquals(successes.toLong(), user.tobyCoins, "coins must equal the number of successful buys")

        val totalSpent = outcomes.filterIsInstance<TradeOutcome.Ok>().sumOf { it.transactedCredits }
        assertEquals(300L - totalSpent, user.socialCredit, "credits accounting must balance")

        val market = marketService.getMarket(fx.guildId)!!
        assertTrue(market.price >= openingPrice, "buys can only push the market up")
    }

    /**
     * Regression guard for lost market-price updates. Each concurrent trade
     * applies buy/sell pressure; without the market row lock, two writes
     * could read the same `market.price` and one update would be clobbered.
     * After the fix the final price equals the deterministic serial product
     * of pressures — independent of execution order.
     */
    @Test
    fun `concurrent trades do not lose market-price updates`() {
        val fx = newFixture(openingCredits = 1_000_000L)
        tradeService.loadOrCreateMarket(fx.guildId)
        val openingPrice = marketService.getMarket(fx.guildId)!!.price

        val buys = 10
        val coinsPerBuy = 1L
        val executor = Executors.newFixedThreadPool(4)
        try {
            (1..buys)
                .map { executor.submit { tradeService.buy(fx.discordId, fx.guildId, coinsPerBuy) } }
                .forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // Pressure is multiplicative: n sequential buys of c coins produce
        // price = p0 * (1 + k*c)^n regardless of order, because multiplication
        // commutes. The lock guarantees each buy sees its predecessor's price.
        val expected = generateSequence(openingPrice) { TobyCoinEngine.applyBuyPressure(it, coinsPerBuy) }
            .drop(1)
            .take(buys)
            .last()
        val actual = marketService.getMarket(fx.guildId)!!.price
        assertEquals(expected, actual, 1e-6, "market price must match serial-application regardless of thread order")

        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(buys.toLong() * coinsPerBuy, user.tobyCoins)
    }
}
