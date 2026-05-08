package web.service

import database.dto.ActivityMonthlyRollupDto
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.LocalDate
import java.time.ZoneOffset

class LeaderboardWebServiceTopGamesTest {

    private val guildId = 42L
    private val thisMonth: LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var userService: UserService
    private lateinit var rollupService: ActivityMonthlyRollupService
    private lateinit var service: LeaderboardWebService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        rollupService = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Test Guild"

        service = LeaderboardWebService(
            jda = jda,
            introWebService = mockk(relaxed = true),
            moderationWebService = mockk(relaxed = true) {
                every { getLeaderboard(guildId) } returns emptyList()
            },
            userService = userService,
            marketService = mockk(relaxed = true),
            titleService = mockk(relaxed = true),
            snapshotService = mockk(relaxed = true),
            membership = GuildMembership(jda),
            rollupService = rollupService,
        )
    }

    private fun rollup(discordId: Long, name: String, seconds: Long) =
        ActivityMonthlyRollupDto(
            discordId = discordId,
            guildId = guildId,
            monthStart = thisMonth,
            activityName = name,
            seconds = seconds
        )

    @Test
    fun `topGames is empty when there are no rollups`() {
        every { rollupService.forGuildMonth(guildId, thisMonth) } returns emptyList()

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertTrue(games.isEmpty())
    }

    @Test
    fun `topGames sums seconds across users for the same activity and ranks descending`() {
        every { rollupService.forGuildMonth(guildId, thisMonth) } returns listOf(
            rollup(1L, "Halo", 1_000),
            rollup(2L, "Halo", 500),     // Halo total = 1500
            rollup(1L, "Tetris", 4_000), // Tetris total = 4000
            rollup(3L, "Chess", 200)
        )

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertEquals(listOf("Tetris", "Halo", "Chess"), games.map { it.name })
        assertEquals(listOf(1, 2, 3), games.map { it.rank })
        assertEquals(4_000L, games[0].seconds)
        assertEquals(1_500L, games[1].seconds)
        assertEquals(200L, games[2].seconds)
    }

    @Test
    fun `topGames excludes rollups owned by users who opted out`() {
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { activityTrackingOptOut = true },
            UserDto(discordId = 2L, guildId = guildId).apply { activityTrackingOptOut = false }
        )
        every { rollupService.forGuildMonth(guildId, thisMonth) } returns listOf(
            rollup(1L, "Halo", 9_999),  // owner opted out -> drop
            rollup(2L, "Halo", 100)
        )

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertEquals(1, games.size)
        assertEquals("Halo", games[0].name)
        assertEquals(100L, games[0].seconds, "opted-out user's hours must not count toward the total")
    }

    @Test
    fun `topGames is capped at TOP_GAMES_LIMIT entries`() {
        val rows = (1..15).map { i -> rollup(i.toLong(), "Game $i", i * 100L) }
        every { rollupService.forGuildMonth(guildId, thisMonth) } returns rows

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertEquals(LeaderboardWebService.TOP_GAMES_LIMIT, games.size)
        assertEquals("Game 15", games.first().name, "biggest playtime takes rank 1")
    }

    @Test
    fun `topGames degrades to empty list when the rollup query throws`() {
        every { rollupService.forGuildMonth(guildId, thisMonth) } throws
            RuntimeException("activity_monthly_rollup unavailable")

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertTrue(games.isEmpty(), "page must still render even if the rollup read fails")
    }

    @Test
    fun `topGameRow formats playtime as hours and minutes`() {
        val row = TopGameRow(rank = 1, name = "Halo", seconds = 3_660L) // 1h 1m
        assertEquals("1h 1m", row.playtimeDisplay)
        assertEquals("0m", TopGameRow(rank = 1, name = "Idle", seconds = 0L).playtimeDisplay)
        assertEquals("5m", TopGameRow(rank = 1, name = "Quick", seconds = 5L * 60).playtimeDisplay)
    }
}
