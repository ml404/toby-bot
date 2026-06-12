package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import common.economy.Coin
import database.configuration.TestDatabaseConfig
import database.dto.user.UserDto
import database.service.economy.EconomyTradeService
import database.service.economy.EconomyTradeService.TradeOutcome
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserCoinHoldingService
import database.service.user.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicLong

/**
 * End-to-end coverage that the V49 multi-coin migration and the
 * `user_coin_holding` table actually work against real Postgres: a
 * non-TOBY coin trades through its own market and holding row while the
 * legacy `user.toby_coins` column is left untouched.
 */
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MultiCoinEconomyIntegrationTest {

    @Autowired lateinit var tradeService: EconomyTradeService
    @Autowired lateinit var marketService: TobyCoinMarketService
    @Autowired lateinit var holdingService: UserCoinHoldingService
    @Autowired lateinit var userService: UserService

    private data class Fixture(val discordId: Long, val guildId: Long)

    private fun newFixture(openingCredits: Long = 1_000_000L): Fixture {
        val id = seq.incrementAndGet()
        // 6_400_000+ keeps this test's ids disjoint from the other integration
        // tests that share the reused Postgres container (TitlesBuyWithTobyCoin
        // uses 800_000+, EconomyTradeService 900_000+) — overlapping ids
        // corrupt each other's users/markets across tests.
        val fx = Fixture(discordId = 6_400_000L + id, guildId = 6_400_000L + id)
        userService.clearCache()
        userService.createNewUser(UserDto(fx.discordId, fx.guildId).apply { socialCredit = openingCredits })
        return fx
    }

    companion object {
        private val seq = AtomicLong()
    }

    @Test
    fun `each coin gets its own market seeded at its initial price`() {
        val fx = newFixture()
        Coin.entries.forEach { coin ->
            val market = tradeService.loadOrCreateMarket(fx.guildId, coin)
            assertEquals(coin.symbol, market.coin)
            assertEquals(coin.initialPrice, market.price, 1e-9)
        }
        // Four independent rows for one guild.
        assertEquals(Coin.entries.size, marketService.listMarkets().count { it.guildId == fx.guildId })
    }

    @Test
    fun `buying a non-TOBY coin fills the holdings table and leaves toby_coins at zero`() {
        val fx = newFixture()

        val outcome = tradeService.buy(fx.discordId, fx.guildId, 15L, coin = Coin.MOON)

        val ok = assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        assertEquals(15L, ok.newCoins)
        assertEquals(15L, holdingService.getAmount(fx.discordId, fx.guildId, Coin.MOON))
        assertEquals(0L, holdingService.getAmount(fx.discordId, fx.guildId, Coin.RUFF), "other coins stay empty")

        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(0L, user.tobyCoins, "a MOON buy must not touch the legacy TOBY balance")

        val moonTrades = marketService.listTradesSince(fx.guildId, java.time.Instant.EPOCH, Coin.MOON)
        assertEquals(1, moonTrades.size)
        assertEquals(Coin.MOON.symbol, moonTrades.single().coin)
    }

    @Test
    fun `selling a non-TOBY coin debits the holding and pays credits`() {
        val fx = newFixture()
        tradeService.buy(fx.discordId, fx.guildId, 40L, coin = Coin.RUFF)
        val creditsAfterBuy = userService.getUserById(fx.discordId, fx.guildId)!!.socialCredit ?: 0L

        val outcome = tradeService.sell(fx.discordId, fx.guildId, 25L, coin = Coin.RUFF)

        assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        assertEquals(15L, holdingService.getAmount(fx.discordId, fx.guildId, Coin.RUFF))
        val creditsAfterSell = userService.getUserById(fx.discordId, fx.guildId)!!.socialCredit ?: 0L
        assertTrue(creditsAfterSell > creditsAfterBuy, "selling RUFF should pay out credits")
    }

    @Test
    fun `TOBY still settles in the legacy column alongside other coins`() {
        val fx = newFixture()
        tradeService.buy(fx.discordId, fx.guildId, 10L, coin = Coin.TOBY)
        tradeService.buy(fx.discordId, fx.guildId, 10L, coin = Coin.MOON)

        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(10L, user.tobyCoins, "TOBY balance lives in user.toby_coins")
        assertEquals(10L, holdingService.getAmount(fx.discordId, fx.guildId, Coin.MOON))
        assertEquals(0L, holdingService.getAmount(fx.discordId, fx.guildId, Coin.TOBY), "TOBY is never in the holdings table")
    }
}
