package database.service

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinPricePointDto
import database.dto.economy.TobyCoinTradeDto
import database.dto.economy.UserCoinHoldingDto
import database.dto.user.UserDto
import database.persistence.economy.UserCoinHoldingPersistence
import database.service.economy.EconomyTradeService
import database.service.economy.EconomyTradeService.TradeOutcome
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.guild.ConfigService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the multi-coin routing in [EconomyTradeService]: TOBY
 * balances live in `user.toby_coins`, every other coin in the
 * `user_coin_holding` table. The integration tests exercise the real DB;
 * these lock down the branch logic without needing a container.
 */
internal class EconomyTradeServiceMultiCoinTest {

    private val discordId = 7L
    private val guildId = 42L

    private lateinit var userService: UserService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var holdingPersistence: UserCoinHoldingPersistence
    private lateinit var service: EconomyTradeService

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        holdingPersistence = mockk(relaxed = true)
        service = EconomyTradeService(
            userService, marketService, jackpotService, configService, holdingPersistence
        )
    }

    private fun user(credits: Long = 1_000_000L, toby: Long = 0L) =
        UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = credits
            tobyCoins = toby
        }

    private fun stubMarket(coin: Coin, price: Double = 100.0) {
        val market = TobyCoinMarketDto(guildId = guildId, coin = coin.symbol, price = price)
        every { marketService.getMarketForUpdate(guildId, coin) } returns market
    }

    @Test
    fun `buying a non-TOBY coin credits the holding row and leaves toby_coins alone`() {
        val u = user(toby = 3L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns u
        stubMarket(Coin.MOON)
        val holding = UserCoinHoldingDto(discordId, guildId, Coin.MOON.symbol, amount = 0L)
        every { holdingPersistence.getForUpdateOrCreate(discordId, guildId, Coin.MOON) } returns holding

        val savedMarket = slot<TobyCoinMarketDto>()
        every { marketService.saveMarket(capture(savedMarket)) } answers { firstArg() }
        val recorded = slot<TobyCoinTradeDto>()
        every { marketService.recordTrade(capture(recorded)) } answers { firstArg() }
        val point = slot<TobyCoinPricePointDto>()
        every { marketService.appendPricePoint(capture(point)) } answers { firstArg() }

        val outcome = service.buy(discordId, guildId, 10L, coin = Coin.MOON)

        val ok = assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        assertEquals(10L, ok.newCoins, "newCoins reflects the MOON holding")
        assertEquals(10L, holding.amount, "holding row credited")
        assertEquals(3L, u.tobyCoins, "TOBY balance untouched by a MOON trade")
        assertEquals(Coin.MOON.symbol, savedMarket.captured.coin)
        assertEquals(Coin.MOON.symbol, recorded.captured.coin)
        assertEquals(Coin.MOON.symbol, point.captured.coin)
        verify { holdingPersistence.save(holding) }
    }

    @Test
    fun `buying TOBY uses toby_coins and never touches the holding table`() {
        val u = user(toby = 5L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns u
        stubMarket(Coin.TOBY)
        val savedMarket = slot<TobyCoinMarketDto>()
        every { marketService.saveMarket(capture(savedMarket)) } answers { firstArg() }

        val outcome = service.buy(discordId, guildId, 4L, coin = Coin.TOBY)

        val ok = assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        assertEquals(9L, ok.newCoins)
        assertEquals(9L, u.tobyCoins)
        assertEquals(Coin.TOBY.symbol, savedMarket.captured.coin)
        verify(exactly = 0) { holdingPersistence.getForUpdateOrCreate(any(), any(), any()) }
        verify(exactly = 0) { holdingPersistence.save(any()) }
    }

    @Test
    fun `selling more of a coin than is held is rejected and never hits the market`() {
        val u = user(toby = 0L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns u
        stubMarket(Coin.RUFF)
        val holding = UserCoinHoldingDto(discordId, guildId, Coin.RUFF.symbol, amount = 5L)
        every { holdingPersistence.getForUpdateOrCreate(discordId, guildId, Coin.RUFF) } returns holding

        val outcome = service.sell(discordId, guildId, 10L, coin = Coin.RUFF)

        val bad = assertInstanceOf(TradeOutcome.InsufficientCoins::class.java, outcome)
        assertEquals(10L, bad.needed)
        assertEquals(5L, bad.have)
        assertEquals(5L, holding.amount, "holding untouched on a rejected sell")
        verify(exactly = 0) { marketService.saveMarket(any()) }
        verify(exactly = 0) { marketService.recordTrade(any()) }
    }

    @Test
    fun `selling a non-TOBY coin debits the holding and pays out credits`() {
        val u = user(credits = 0L, toby = 0L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns u
        stubMarket(Coin.RUFF, price = 100.0)
        val holding = UserCoinHoldingDto(discordId, guildId, Coin.RUFF.symbol, amount = 50L)
        every { holdingPersistence.getForUpdateOrCreate(discordId, guildId, Coin.RUFF) } returns holding
        every { marketService.saveMarket(any()) } answers { firstArg() }

        val outcome = service.sell(discordId, guildId, 20L, coin = Coin.RUFF)

        val ok = assertInstanceOf(TradeOutcome.Ok::class.java, outcome)
        assertEquals(30L, holding.amount, "holding debited by the sold amount")
        assertEquals(30L, ok.newCoins)
        assertEquals(0L, u.tobyCoins, "TOBY untouched")
        org.junit.jupiter.api.Assertions.assertTrue(ok.newCredits > 0L, "credits paid out")
        verify { holdingPersistence.save(holding) }
    }

    @Test
    fun `a non-positive amount is rejected before any lookup`() {
        assertInstanceOf(TradeOutcome.InvalidAmount::class.java, service.buy(discordId, guildId, 0L, coin = Coin.MOON))
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { holdingPersistence.getForUpdateOrCreate(any(), any(), any()) }
    }

    @Test
    fun `sellToCover sells nothing and reports not covered when capacity is below the shortfall`() {
        every { userService.getUserById(discordId, guildId) } returns user(toby = 2L)
        // A near-worthless TOBY market and no other holdings → tiny capacity.
        every { marketService.getMarket(guildId, Coin.TOBY) } returns
                TobyCoinMarketDto(guildId = guildId, coin = Coin.TOBY.symbol, price = 1.0)

        val result = service.sellToCover(discordId, guildId, shortfall = 500L)

        org.junit.jupiter.api.Assertions.assertFalse(result.covered)
        assertEquals(0L, result.creditsRaised)
        org.junit.jupiter.api.Assertions.assertTrue(result.capacity < 500L)
        verify(exactly = 0) { marketService.saveMarket(any()) }
    }

    @Test
    fun `sellToCover covers a shortfall from TOBY first`() {
        val u = user(credits = 0L, toby = 1_000L)
        every { userService.getUserById(discordId, guildId) } returns u
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns u
        every { marketService.getMarket(guildId, Coin.TOBY) } returns
                TobyCoinMarketDto(guildId = guildId, coin = Coin.TOBY.symbol, price = 100.0)
        stubMarket(Coin.TOBY, price = 100.0)   // for the locked sell
        every { marketService.saveMarket(any()) } answers { firstArg() }

        val result = service.sellToCover(discordId, guildId, shortfall = 500L)

        org.junit.jupiter.api.Assertions.assertTrue(result.covered)
        org.junit.jupiter.api.Assertions.assertTrue(result.tobyCoinsSold > 0L, "TOBY leg should be the one sold")
        org.junit.jupiter.api.Assertions.assertTrue(result.creditsRaised >= 500L)
        org.junit.jupiter.api.Assertions.assertTrue(u.tobyCoins < 1_000L, "TOBY balance debited")
    }

    @Test
    fun `sellToCover is a covered no-op for a non-positive shortfall`() {
        val result = service.sellToCover(discordId, guildId, shortfall = 0L)
        org.junit.jupiter.api.Assertions.assertTrue(result.covered)
        assertEquals(0L, result.creditsRaised)
        verify(exactly = 0) { userService.getUserById(any(), any()) }
    }
}
