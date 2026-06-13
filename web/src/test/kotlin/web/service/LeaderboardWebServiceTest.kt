package web.service

import database.service.guild.AchievementService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
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
import web.util.GuildMembership

class LeaderboardWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var moderationWebService: ModerationWebService
    private lateinit var userService: UserService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var achievementService: AchievementService
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
        achievementService = mockk(relaxed = true)
        // Real GuildMembership over the mocked JDA — keeps the existing
        // `isMember` tests black-boxed against JDA, not against the helper.
        service = LeaderboardWebService(
            jda, introWebService, moderationWebService, userService, marketService,
            mockk(relaxed = true), mockk(relaxed = true),
            GuildMembership(jda),
            mockk(relaxed = true),
            achievementService,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
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
        // standings carries the full ranked list (top 3 included) so the
        // Members tab is self-contained — the podium above the tabs is a
        // visual hero, not a substitute for the top rows of the table.
        assertEquals(5, view.standings.size)
        assertEquals(1, view.standings[0].rank)
        assertEquals("A", view.standings[0].name)
        assertEquals(5, view.standings.last().rank)
        assertEquals(5, view.totalMembers)
        assertEquals(100L, view.totalCreditsThisMonth)
        assertEquals(3600L, view.totalVoiceThisMonth)
        assertEquals("A", view.mostActiveMember)
    }

    @Test
    fun `getGuildView leaves mostActiveMember null when no one has voice this month`() {
        // Regression: rawRows arrive ordered by current-total socialCredit desc.
        // If everyone's voiceSecondsThisMonth is 0, naive maxByOrNull picks the
        // first row (the lifetime leader) and mislabels them as "Most active
        // this month". Should be null so the template hides the stat card.
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Quiet"
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Whale", avatarUrl = null,
                socialCredit = 10_000, voiceSecondsThisMonth = 0),
            LeaderboardRow(rank = 2, discordId = "2", name = "Minnow", avatarUrl = null,
                socialCredit = 100, voiceSecondsThisMonth = 0)
        )

        val view = service.getGuildView(guildId)!!
        assertNull(view.mostActiveMember)
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
        // The single member is on the podium AND in the standings table —
        // the Members tab should not appear empty just because the guild
        // is tiny.
        assertEquals(1, view.standings.size)
        assertEquals("Solo", view.standings[0].name)
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
                title = "🥇", voiceSecondsThisMonth = 1800, creditsEarnedThisMonth = 250),
            // Bigger lifetime balance but no earnings this month — should NOT be
            // surfaced as the picker's top this-month earner.
            LeaderboardRow(rank = 2, discordId = "2", name = "Stale", avatarUrl = null, socialCredit = 5000,
                title = null, voiceSecondsThisMonth = 0, creditsEarnedThisMonth = 0)
        )

        val cards = service.getGuildsWhereUserCanView("token", discordId)
        assertEquals(1, cards.size)
        assertEquals("42", cards.first().id)
        assertEquals("Ace", cards.first().topName)
        assertEquals("🥇", cards.first().topTitle)
        assertEquals(250L, cards.first().topCreditsThisMonth)
        assertEquals(1800L, cards.first().totalVoiceSeconds)
    }

    @Test
    fun `getGuildsWhereUserCanView leaves topName null when no one earned this month`() {
        val mutuals = listOf(GuildInfo(id = "42", name = "Quiet", iconHash = null))
        every { introWebService.getMutualGuilds("token") } returns mutuals
        val guild42 = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(42L) } returns guild42
        every { guild42.getMemberById(discordId) } returns mockk<Member>(relaxed = true)
        // Lifetime balances exist but creditsEarnedThisMonth is 0 for everyone —
        // start of the month, fresh baselines, etc. Don't claim a "top earner".
        every { moderationWebService.getLeaderboard(42L) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Ace", avatarUrl = null,
                socialCredit = 1000, creditsEarnedThisMonth = 0),
            LeaderboardRow(rank = 2, discordId = "2", name = "Bee", avatarUrl = null,
                socialCredit = 500, creditsEarnedThisMonth = 0)
        )

        val cards = service.getGuildsWhereUserCanView("token", discordId)
        assertEquals(1, cards.size)
        assertEquals(null, cards.first().topName)
        assertEquals(0L, cards.first().topCreditsThisMonth)
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
        assertEquals(LeaderboardSort.XP, LeaderboardSort.fromQuery("xp"))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery(null))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery(""))
        assertEquals(LeaderboardSort.THIS_MONTH, LeaderboardSort.fromQuery("garbage"))
    }

    @Test
    fun `getGuildView builds champions from achievement progress sorted by total wins`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Champions test"
        every { moderationWebService.getLeaderboard(guildId) } returns emptyList()
        // Discord 1 = 5 RPS + 3 TTT = 8 wins. Discord 2 = 10 duel wins.
        // Discord 3 = 1 connect4 win. Sorted desc: 2 (10), 1 (8), 3 (1).
        every { achievementService.progressByCodesForGuild(guildId, LeaderboardWebService.CHAMPIONS_WIN_CODES) } returns listOf(
            AchievementService.ProgressByCode(discordId = 1L, code = "rps_wins_10", progress = 5L),
            AchievementService.ProgressByCode(discordId = 1L, code = "tictactoe_wins_10", progress = 3L),
            AchievementService.ProgressByCode(discordId = 2L, code = "duel_wins_10", progress = 10L),
            AchievementService.ProgressByCode(discordId = 3L, code = "connect4_wins_10", progress = 1L),
        )
        every { guild.getMemberById(any<Long>()) } returns null

        val view = service.getGuildView(guildId)!!

        assertEquals(3, view.champions.size, "all three users with non-zero wins show up")
        assertEquals(2L, view.champions[0].discordId.toLong())
        assertEquals(10L, view.champions[0].totalWins)
        assertEquals(1, view.champions[0].rank)
        assertEquals(1L, view.champions[1].discordId.toLong())
        assertEquals(8L, view.champions[1].totalWins)
        assertEquals(3L, view.champions[2].discordId.toLong())
        assertEquals(1L, view.champions[2].totalWins)
    }

    @Test
    fun `getGuildView returns empty champions when achievement service fails`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Failure test"
        every { moderationWebService.getLeaderboard(guildId) } returns emptyList()
        every { achievementService.progressByCodesForGuild(any(), any()) } throws RuntimeException("DB down")

        // The view must still render — Champions becomes empty rather than 500-ing the page.
        val view = service.getGuildView(guildId)!!
        assertTrue(view.champions.isEmpty())
    }

    @Test
    fun `getGuildView with XP sort ranks by xp desc and reassigns ranks`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "XP sort test"
        // Alice has the most credits, Carol has the most XP — XP sort surfaces Carol.
        every { moderationWebService.getLeaderboard(guildId) } returns listOf(
            LeaderboardRow(rank = 1, discordId = "1", name = "Alice", avatarUrl = null,
                socialCredit = 1_000, xp = 100L, creditsEarnedThisMonth = 10),
            LeaderboardRow(rank = 2, discordId = "2", name = "Bob", avatarUrl = null,
                socialCredit = 500, xp = 500L, creditsEarnedThisMonth = 0),
            LeaderboardRow(rank = 3, discordId = "3", name = "Carol", avatarUrl = null,
                socialCredit = 200, xp = 5_000L, creditsEarnedThisMonth = 0)
        )

        val view = service.getGuildView(guildId, LeaderboardSort.XP)!!

        assertEquals(LeaderboardSort.XP, view.sort)
        assertEquals("Carol", view.podium[0].name)
        assertEquals(1, view.podium[0].rank)
        assertEquals("Bob", view.podium[1].name)
        assertEquals("Alice", view.podium[2].name)
    }
}
