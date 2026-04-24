package web.service

import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeaderboardWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var moderationWebService: ModerationWebService
    private lateinit var userService: UserService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var service: LeaderboardWebService

    private val guildId = 42L
    private val discordId = 100L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        moderationWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        service = LeaderboardWebService(jda, introWebService, moderationWebService, userService, marketService, mockk(relaxed = true))
    }

    @Test
    fun `isMember returns true when user is in the guild`() {
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        assertTrue(service.isMember(discordId, guildId))
    }

    @Test
    fun `isMember returns false when user is not in the guild`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns null
        assertFalse(service.isMember(discordId, guildId))
    }

    @Test
    fun `isMember returns false when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertFalse(service.isMember(discordId, guildId))
    }

    @Test
    fun `getGuildView returns null when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildView(guildId))
    }

    @Test
    fun `getGuildView splits leaderboard into podium (top 3) and standings`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test"
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "A", avatarUrl = null, socialCredit = 500,
                title = "⭐", voiceSecondsThisMonth = 3600, creditsEarnedThisMonth = 100),
            LeaderboardRow(rank = 2, discordId = "2", name = "B", avatarUrl = null, socialCredit = 400),
            LeaderboardRow(rank = 3, discordId = "3", name = "C", avatarUrl = null, socialCredit = 300),
            LeaderboardRow(rank = 4, discordId = "4", name = "D", avatarUrl = null, socialCredit = 200),
            LeaderboardRow(rank = 5, discordId = "5", name = "E", avatarUrl = null, socialCredit = 100)
        )

        val view = service.getGuildView(guildId)!!

        assertEquals(3, view.podium.size)
        assertEquals("A", view.podium[0].name)
        assertEquals(2, view.standings.size)
        assertEquals(4, view.standings[0].rank)
        assertEquals(5, view.totalMembers)
        assertEquals(100L, view.totalCreditsThisMonth)
        assertEquals(3600L, view.totalVoiceThisMonth)
        assertEquals("A", view.mostActiveMember)
    }

    @Test
    fun `getGuildView with fewer than 3 users returns only what exists`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Small"
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Solo", avatarUrl = null, socialCredit = 50)
        )

        val view = service.getGuildView(guildId)!!
        assertEquals(1, view.podium.size)
        assertTrue(view.standings.isEmpty())
    }

    @Test
    fun `getGuildsWhereUserCanView filters to guilds the user belongs to`() {
        val mutuals = listOf(
            GuildInfo(id = "42", name = "Alpha", iconHash = null),
            GuildInfo(id = "43", name = "Beta", iconHash = null)
        )
        every { introWebService.getMutualGuilds("token") } returns mutuals
        val guild42 = mockk<Guild>(relaxed = true)
        val guild43 = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(42L) } returns guild42
        every { jda.getGuildById(43L) } returns guild43
        val memberMock = mockk<Member>(relaxed = true)
        every { guild42.getMemberById(discordId) } returns memberMock
        every { guild43.getMemberById(discordId) } returns null // user not a member of Beta
        every { moderationWebService.getLeaderboard(42L) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Ace", avatarUrl = null, socialCredit = 1000,
                title = "🥇", voiceSecondsThisMonth = 1800)
        )

        val cards = service.getGuildsWhereUserCanView("token", discordId)
        assertEquals(1, cards.size)
        assertEquals("42", cards.first().id)
        assertEquals("Ace", cards.first().topName)
        assertEquals("🥇", cards.first().topTitle)
        assertEquals(1000L, cards.first().topCredits)
        assertEquals(1800L, cards.first().totalVoiceSeconds)
    }

    // ---- sort behaviour ----

    @Test
    fun `getGuildView defaults to THIS_MONTH and ranks by creditsEarnedThisMonth`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Sort test"
        // Alice has the biggest lifetime, Bob is the biggest earner THIS month.
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Alice", avatarUrl = null,
                socialCredit = 1_000, creditsEarnedThisMonth = 10),
            LeaderboardRow(rank = 2, discordId = "2", name = "Bob", avatarUrl = null,
                socialCredit = 500, creditsEarnedThisMonth = 400),
            LeaderboardRow(rank = 3, discordId = "3", name = "Carol", avatarUrl = null,
                socialCredit = 200, creditsEarnedThisMonth = 50)
        )

        val view = service.getGuildView(guildId)!!

        assertEquals(LeaderboardSort.THIS_MONTH, view.sort)
        assertEquals("Bob", view.podium[0].name, "top of podium in month mode is biggest monthly earner")
        assertEquals(1, view.podium[0].rank, "rank reassigned to match the active sort")
        assertEquals("Carol", view.podium[1].name)
        assertEquals("Alice", view.podium[2].name)
    }

    @Test
    fun `getGuildView with LIFETIME sort preserves the moderation service order`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Sort test"
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Alice", avatarUrl = null,
                socialCredit = 1_000, creditsEarnedThisMonth = 10),
            LeaderboardRow(rank = 2, discordId = "2", name = "Bob", avatarUrl = null,
                socialCredit = 500, creditsEarnedThisMonth = 400),
            LeaderboardRow(rank = 3, discordId = "3", name = "Carol", avatarUrl = null,
                socialCredit = 200, creditsEarnedThisMonth = 50)
        )

        val view = service.getGuildView(guildId, LeaderboardSort.LIFETIME)!!

        assertEquals(LeaderboardSort.LIFETIME, view.sort)
        assertEquals("Alice", view.podium[0].name)
        assertEquals(1, view.podium[0].rank)
        assertEquals("Bob", view.podium[1].name)
        assertEquals("Carol", view.podium[2].name)
    }

    @Test
    fun `LeaderboardSort fromQuery maps tokens and falls back to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery("month"))
        assertEquals(LeaderboardSort.LIFETIME, LeaderboardSort.fromQuery("lifetime"))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery(null))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery(""))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery("garbage"))
    }
}
