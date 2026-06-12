package web.service

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.UserPriceTriggerDto
import database.dto.user.UserDto
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserCoinHoldingService
import database.service.economy.UserPriceTriggerService
import database.service.user.UserNotificationPrefService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.Instant

/**
 * Multi-coin behaviour of the /economy web service: which coin a page is
 * for is threaded through every lookup, non-TOBY balances come from the
 * holdings table, and the view carries the selector tabs.
 */
internal class EconomyWebServiceMultiCoinTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var userService: UserService
    private lateinit var priceTriggerService: UserPriceTriggerService
    private lateinit var notificationPrefService: UserNotificationPrefService
    private lateinit var holdingService: UserCoinHoldingService
    private lateinit var service: EconomyWebService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        priceTriggerService = mockk(relaxed = true)
        notificationPrefService = mockk(relaxed = true)
        holdingService = mockk(relaxed = true)
        service = EconomyWebService(
            jda, introWebService, tradeService, marketService, userService,
            GuildMembership(jda), priceTriggerService, notificationPrefService, holdingService,
        )
        val guild = mockk<Guild>(relaxed = true)
        every { guild.name } returns "Test Guild"
        every { jda.getGuildById(guildId) } returns guild
    }

    private fun market(coin: Coin, price: Double) =
        TobyCoinMarketDto(guildId = guildId, coin = coin.symbol, price = price, lastTickAt = Instant.now())

    private fun trigger(coin: Coin, id: Long) = UserPriceTriggerDto(
        id = id, discordId = discordId, guildId = guildId, coin = coin.symbol,
        thresholdPrice = 80.0, priceAtCreation = 100.0,
        side = UserPriceTriggerDto.Side.BUY.name, amount = 5L, enabled = true,
    )

    @Test
    fun `non-TOBY view reads the holdings table and the per-coin trade impact`() {
        every { tradeService.loadOrCreateMarket(guildId, Coin.MOON) } returns market(Coin.MOON, 50.0)
        every { userService.getUserById(discordId, guildId) } returns
                UserDto(discordId, guildId).apply { tobyCoins = 7L; socialCredit = 100L }
        every { holdingService.getAmount(discordId, guildId, Coin.MOON) } returns 12L

        val view = service.getEconomyView(guildId, discordId, Coin.MOON)!!

        assertEquals("MOON", view.coin)
        assertEquals(Coin.MOON.displayName, view.coinName)
        assertEquals(Coin.MOON.riskLabel, view.riskLabel)
        assertEquals(12L, view.coins, "balance comes from holdings, not toby_coins")
        assertEquals(Coin.MOON.tradeImpact, view.tradeImpact, 1e-12)
        assertEquals((12L * 50.0).toLong(), view.portfolioCredits)
        assertEquals(Coin.entries.size, view.coinOptions.size)
        assertTrue(view.coinOptions.first { it.symbol == "MOON" }.selected)
        assertTrue(view.coinOptions.count { it.selected } == 1)
        verify(exactly = 0) { holdingService.getAmount(discordId, guildId, Coin.TOBY) }
    }

    @Test
    fun `TOBY view reads toby_coins and never the holdings table`() {
        every { tradeService.loadOrCreateMarket(guildId, Coin.TOBY) } returns market(Coin.TOBY, 100.0)
        every { userService.getUserById(discordId, guildId) } returns
                UserDto(discordId, guildId).apply { tobyCoins = 9L; socialCredit = 100L }

        val view = service.getEconomyView(guildId, discordId, Coin.TOBY)!!

        assertEquals(9L, view.coins)
        assertEquals("TOBY", view.coin)
        verify(exactly = 0) { holdingService.getAmount(any(), any(), any()) }
    }

    @Test
    fun `history and trades are fetched for the requested coin`() {
        every { marketService.listHistory(guildId, any(), Coin.RUFF) } returns emptyList()
        every { marketService.listTradesSince(guildId, any(), Coin.RUFF) } returns emptyList()

        service.getHistory(guildId, "1d", Coin.RUFF)
        service.getTrades(guildId, "1d", Coin.RUFF)

        verify(exactly = 1) { marketService.listHistory(guildId, any(), Coin.RUFF) }
        verify(exactly = 1) { marketService.listTradesSince(guildId, any(), Coin.RUFF) }
    }

    @Test
    fun `listWatches returns only the requested coin's triggers`() {
        every { priceTriggerService.listForUser(discordId, guildId) } returns listOf(
            trigger(Coin.TOBY, 1L),
            trigger(Coin.MOON, 2L),
            trigger(Coin.MOON, 3L),
        )

        val moon = service.listWatches(discordId, guildId, Coin.MOON)
        assertEquals(listOf(2L, 3L), moon.map { it.id })

        val toby = service.listWatches(discordId, guildId, Coin.TOBY)
        assertEquals(listOf(1L), toby.map { it.id })
    }

    @Test
    fun `getPortfolio lists every held coin with its value plus the credit balance`() {
        every { userService.getUserById(discordId, guildId) } returns
                UserDto(discordId, guildId).apply { tobyCoins = 5L; socialCredit = 250L }
        every { holdingService.getAmount(discordId, guildId, Coin.MOON) } returns 10L
        every { marketService.getMarket(guildId, Coin.TOBY) } returns market(Coin.TOBY, 100.0)
        every { marketService.getMarket(guildId, Coin.MOON) } returns market(Coin.MOON, 50.0)

        val view = service.getPortfolio(guildId, discordId)!!

        assertEquals(250L, view.credits)
        // Only the coins with a positive balance appear, in catalogue order.
        assertEquals(listOf("TOBY", "MOON"), view.holdings.map { it.symbol })
        val toby = view.holdings.first { it.symbol == "TOBY" }
        assertEquals(5L, toby.amount)
        assertEquals(500L, toby.value, "5 TOBY @ 100")
        val moon = view.holdings.first { it.symbol == "MOON" }
        assertEquals(10L, moon.amount)
        assertEquals(500L, moon.value, "10 MOON @ 50")
        assertEquals(1_000L, view.totalCoinValue)
    }

    @Test
    fun `getPortfolio returns null when the bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getPortfolio(guildId, discordId))
    }

    @Test
    fun `createWatch forwards the coin to the trigger service`() {
        every { tradeService.loadOrCreateMarket(guildId, Coin.RUFF) } returns market(Coin.RUFF, 100.0)
        every {
            priceTriggerService.create(any(), any(), any(), any(), any(), any(), Coin.RUFF)
        } returns trigger(Coin.RUFF, 9L)

        service.createWatch(discordId, guildId, 80.0, UserPriceTriggerDto.Side.BUY, 5L, Coin.RUFF)

        verify(exactly = 1) {
            priceTriggerService.create(discordId, guildId, 80.0, 100.0, UserPriceTriggerDto.Side.BUY, 5L, Coin.RUFF)
        }
    }
}
