package web.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.TobyCoinTradeDto
import database.dto.UserDto
import database.service.EconomyTradeService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

        val view = service.getEconomyView(guildId, discordId)

        assertNotNull(view)
        assertEquals("Test Guild", view!!.guildName)
        assertEquals(4L, view.coins)
        assertEquals(200L, view.credits)
        assertEquals(600L, view.portfolioCredits) // 4 coins * 150.0 = 600
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

    @Test
    fun `getTrades resolves member display name and maps fields`() {
        val now = Instant.now()
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(7L) } returns member
        every { member.effectiveName } returns "FratLayton"
        every { marketService.listTradesSince(guildId, any()) } returns listOf(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 7L, side = "BUY",
                amount = 5L, pricePerCoin = 12.5, executedAt = now
            )
        )

        val markers = service.getTrades(guildId, "1d")

        assertEquals(1, markers.size)
        val m = markers.single()
        assertEquals(now.toEpochMilli(), m.t)
        assertEquals("BUY", m.side)
        assertEquals(5L, m.amount)
        assertEquals(12.5, m.price)
        assertEquals("FratLayton", m.name)
    }

    @Test
    fun `getTrades falls back to Unknown when member is not in guild cache`() {
        val now = Instant.now()
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(any<Long>()) } returns null
        every { marketService.listTradesSince(guildId, any()) } returns listOf(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 99L, side = "SELL",
                amount = 1L, pricePerCoin = 9.0, executedAt = now
            )
        )

        val markers = service.getTrades(guildId, "1d")

        assertEquals("Unknown", markers.single().name)
    }

    @Test
    fun `getTrades returns empty list without hitting JDA when there are no trades`() {
        every { marketService.listTradesSince(guildId, any()) } returns emptyList()
        val markers = service.getTrades(guildId, "1d")
        assertTrue(markers.isEmpty())
    }

    @Test
    fun `getGuildMembers returns empty when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(service.getGuildMembers(guildId).isEmpty())
    }

    @Test
    fun `getGuildMembers filters bots and sorts by lowercase name`() {
        val guild = mockk<Guild>(relaxed = true)
        val human = mockk<Member>(relaxed = true)
        val humanUser = mockk<User>(relaxed = true)
        val botMember = mockk<Member>(relaxed = true)
        val botUser = mockk<User>(relaxed = true)
        val zUser = mockk<User>(relaxed = true)
        val zMember = mockk<Member>(relaxed = true)
        every { humanUser.isBot } returns false
        every { human.user } returns humanUser
        every { human.id } returns "100"
        every { human.effectiveName } returns "alice"
        every { botUser.isBot } returns true
        every { botMember.user } returns botUser
        every { botMember.id } returns "999"
        every { botMember.effectiveName } returns "TobyBot"
        every { zUser.isBot } returns false
        every { zMember.user } returns zUser
        every { zMember.id } returns "200"
        every { zMember.effectiveName } returns "Zed"
        every { guild.members } returns listOf(zMember, botMember, human)
        every { jda.getGuildById(guildId) } returns guild

        val members = service.getGuildMembers(guildId)

        assertEquals(2, members.size)
        assertFalse(members.any { it.id == "999" })
        assertEquals("alice", members[0].name)
        assertEquals("Zed", members[1].name)
    }
}
