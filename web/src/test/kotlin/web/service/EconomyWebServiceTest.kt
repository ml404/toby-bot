package web.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.UserDto
import database.service.EconomyTradeService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class EconomyWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var userService: UserService
    private lateinit var service: EconomyWebService

    private val guildId = 42L
    private val discordId = 100L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = EconomyWebService(jda, introWebService, tradeService, marketService, userService)
    }

    @Test
    fun `getEconomyView returns null when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getEconomyView(guildId, discordId))
    }

    @Test
    fun `getEconomyView computes portfolio value from market price and coin balance`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.name } returns "Test Guild"
        every { jda.getGuildById(guildId) } returns guild
        every { tradeService.loadOrCreateMarket(guildId) } returns TobyCoinMarketDto(
            guildId = guildId, price = 150.0, lastTickAt = Instant.now()
        )
        every { userService.getUserById(discordId, guildId) } returns UserDto(discordId, guildId).apply {
            socialCredit = 200L
            tobyCoins = 4L
        }
        every { marketService.listHistory(guildId, any()) } returns emptyList()

        val view = service.getEconomyView(guildId, discordId)

        assertNotNull(view)
        assertEquals("Test Guild", view!!.guildName)
        assertEquals(4L, view.coins)
        assertEquals(200L, view.credits)
        assertEquals(600L, view.portfolioCredits) // 4 coins * 150.0 = 600
        assertNull(view.change24h)
    }

    @Test
    fun `getHistory maps samples to t, price pairs`() {
        val now = Instant.now()
        every { marketService.listHistory(guildId, any()) } returns listOf(
            TobyCoinPricePointDto(guildId = guildId, sampledAt = now, price = 100.0)
        )
        val points = service.getHistory(guildId, "1d")
        assertEquals(1, points.size)
        assertEquals(now.toEpochMilli(), points[0].t)
        assertEquals(100.0, points[0].price)
    }
}
