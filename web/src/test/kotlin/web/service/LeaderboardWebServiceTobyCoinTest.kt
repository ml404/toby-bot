package web.service

import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class LeaderboardWebServiceTobyCoinTest {

    private val guildId = 42L

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var userService: UserService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var service: LeaderboardWebService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Test Guild"

        service = LeaderboardWebService(
            jda = jda,
            introWebService = mockk(relaxed = true),
            moderationWebService = mockk(relaxed = true) {
                every { getLeaderboard(guildId) } returns emptyList()
            },
            userService = userService,
            marketService = marketService
        )
    }

    private fun member(id: Long, name: String): Member = mockk(relaxed = true) {
        every { effectiveName } returns name
        every { effectiveAvatarUrl } returns "https://cdn.discord/$id.png"
    }

    @Test
    fun `tobyCoinLeaders sorts by coins desc filtering zero-coin holders and computes portfolio`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 2.5, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 100L },
            UserDto(discordId = 2L, guildId = guildId).apply { tobyCoins = 50L },
            UserDto(discordId = 3L, guildId = guildId).apply { tobyCoins = 0L },   // filtered out
            UserDto(discordId = 4L, guildId = guildId).apply { tobyCoins = 200L }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { guild.getMemberById(2L) } returns member(2L, "Bob")
        every { guild.getMemberById(4L) } returns member(4L, "Carol")

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(3, leaders.size, "zero-coin holder should be filtered out")
        assertEquals(listOf("Carol", "Alice", "Bob"), leaders.map { it.name })
        assertEquals(listOf(1, 2, 3), leaders.map { it.rank })
        // portfolio = floor(coins * price)
        assertEquals(500L, leaders[0].portfolioCredits) // 200 * 2.5
        assertEquals(250L, leaders[1].portfolioCredits) // 100 * 2.5
        assertEquals(125L, leaders[2].portfolioCredits) // 50  * 2.5
    }

    @Test
    fun `tobyCoinLeaders is empty when no one has coins`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 100.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 0L },
            UserDto(discordId = 2L, guildId = guildId).apply { tobyCoins = 0L }
        )

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertTrue(leaders.isEmpty())
    }

    @Test
    fun `tobyCoinLeaders uses zero price gracefully when no market exists`() {
        every { marketService.getMarket(guildId) } returns null
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 10L }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(1, leaders.size)
        assertEquals(0L, leaders[0].portfolioCredits, "portfolio = 0 when price unknown")
        assertEquals(10L, leaders[0].coins)
    }

    @Test
    fun `tobyCoinLeaders respects the page limit`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        val users = (1..15L).map { i ->
            UserDto(discordId = i, guildId = guildId).apply { tobyCoins = i }
        }
        every { userService.listGuildUsers(guildId) } returns users
        users.forEach { dto ->
            every { guild.getMemberById(dto.discordId) } returns member(dto.discordId, "U${dto.discordId}")
        }

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(LeaderboardWebService.TOBY_COIN_LEADERBOARD_LIMIT, leaders.size)
        assertEquals("U15", leaders.first().name)
    }
}
