package web.service

import database.dto.ActivityMonthlyRollupDto
import database.dto.UserDto
import database.service.ActivityMonthlyRollupService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.LocalDate
import java.time.ZoneOffset

class LeaderboardWebServiceTopGamesTest {

    private val guildId = 42L
    private val thisMonth: LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
    private val oldest: LocalDate = thisMonth.minusMonths(11)

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
        // Default: members resolve to null so contributors fall back to "Unknown".
        every { guild.getMemberById(any<Long>()) } returns null

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
            achievementService = mockk(relaxed = true),
        )
    }

    private fun rollup(discordId: Long, name: String, seconds: Long, month: LocalDate = thisMonth) =
        ActivityMonthlyRollupDto(
            discordId = discordId,
            guildId = guildId,
            monthStart = month,
            activityName = name,
            seconds = seconds
        )

    private fun stubSince(vararg rows: ActivityMonthlyRollupDto) {
        every { rollupService.forGuildSince(guildId, oldest) } returns rows.toList()
    }

    @Test
    fun `topGames is empty when there are no rollups`() {
        stubSince()

        val view = checkNotNull(service.getGuildView(guildId))

        assertTrue(view.topGames.isEmpty())
        assertEquals(12, view.topGamesMonthOptions.size, "picker always offers 12 months")
        assertEquals(thisMonth, view.topGamesMonth, "defaults to current month")
    }

    @Test
    fun `topGames sums seconds across users for the same activity and ranks descending`() {
        stubSince(
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
        stubSince(
            rollup(1L, "Halo", 9_999),  // owner opted out -> drop
            rollup(2L, "Halo", 100)
        )

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertEquals(1, games.size)
        assertEquals("Halo", games[0].name)
        assertEquals(100L, games[0].seconds, "opted-out user's hours must not count toward the total")
        assertTrue(
            games[0].contributors.none { it.discordId == "1" },
            "opted-out user must not appear in the contributors tooltip either"
        )
    }

    @Test
    fun `topGames is capped at TOP_GAMES_LIMIT entries`() {
        val rows = (1..15).map { i -> rollup(i.toLong(), "Game $i", i * 100L) }
        stubSince(*rows.toTypedArray())

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        assertEquals(LeaderboardWebService.TOP_GAMES_LIMIT, games.size)
        assertEquals("Game 15", games.first().name, "biggest playtime takes rank 1")
    }

    @Test
    fun `topGames degrades to empty list when the rollup query throws`() {
        every { rollupService.forGuildSince(guildId, oldest) } throws
            RuntimeException("activity_monthly_rollup unavailable")

        val view = checkNotNull(service.getGuildView(guildId))

        assertTrue(view.topGames.isEmpty(), "page must still render even if the rollup read fails")
        // The picker still renders 12 months — none have data.
        assertEquals(12, view.topGamesMonthOptions.size)
        assertTrue(view.topGamesMonthOptions.none { it.hasData })
    }

    @Test
    fun `topGameRow formats playtime as hours and minutes`() {
        val row = TopGameRow(rank = 1, name = "Halo", seconds = 3_660L) // 1h 1m
        assertEquals("1h 1m", row.playtimeDisplay)
        assertEquals("0m", TopGameRow(rank = 1, name = "Idle", seconds = 0L).playtimeDisplay)
        assertEquals("5m", TopGameRow(rank = 1, name = "Quick", seconds = 5L * 60).playtimeDisplay)
    }

    // ---- New coverage: contributors, deltas, history, month picker, sparkline ----

    @Test
    fun `contributors are sorted desc, capped, and carry percent of total`() {
        val rows = (1..10).map { i -> rollup(i.toLong(), "Halo", (11 - i) * 100L) }
        stubSince(*rows.toTypedArray())

        val games = checkNotNull(service.getGuildView(guildId)).topGames

        val contributors = games.single().contributors
        assertEquals(LeaderboardWebService.TOP_CONTRIBUTORS_LIMIT, contributors.size)
        // Sorted by seconds desc — discord id 1 had the most, id 8 the least in the top 8.
        assertEquals("1", contributors.first().discordId)
        assertTrue(contributors.zipWithNext().all { (a, b) -> a.seconds >= b.seconds })

        val percentSum = contributors.sumOf { it.percent }
        assertTrue(percentSum in 60..100, "Top 8 of 10 should account for the bulk of the total; was $percentSum")
        assertTrue(contributors.all { it.percent in 0..100 })
    }

    @Test
    fun `contributor name resolves from guild member when available`() {
        val member = mockk<Member>(relaxed = true).also {
            every { it.effectiveName } returns "Alice"
            every { it.effectiveAvatarUrl } returns "https://example/avatar.png"
        }
        every { guild.getMemberById(7L) } returns member
        stubSince(rollup(7L, "Halo", 600))

        val contrib = checkNotNull(service.getGuildView(guildId)).topGames.single().contributors.single()
        assertEquals("Alice", contrib.name)
        assertEquals("https://example/avatar.png", contrib.avatarUrl)
    }

    @Test
    fun `delta vs last month is positive, negative, zero, and NEW`() {
        val lastMonth = thisMonth.minusMonths(1)
        stubSince(
            // Rising: 500 last, 800 this -> +300
            rollup(1L, "Rising", 500, lastMonth),
            rollup(1L, "Rising", 800, thisMonth),
            // Falling: 1000 last, 600 this -> -400
            rollup(1L, "Falling", 1_000, lastMonth),
            rollup(1L, "Falling", 600, thisMonth),
            // Flat: 200 last, 200 this -> 0
            rollup(1L, "Flat", 200, lastMonth),
            rollup(1L, "Flat", 200, thisMonth),
            // NEW: nothing last, 700 this
            rollup(1L, "Newbie", 700, thisMonth)
        )

        val byName = checkNotNull(service.getGuildView(guildId)).topGames.associateBy { it.name }

        assertEquals(300L, byName.getValue("Rising").deltaSeconds)
        assertFalse(byName.getValue("Rising").isNew)
        assertEquals(-400L, byName.getValue("Falling").deltaSeconds)
        assertEquals(0L, byName.getValue("Flat").deltaSeconds)
        assertEquals(700L, byName.getValue("Newbie").deltaSeconds)
        assertTrue(byName.getValue("Newbie").isNew)
    }

    @Test
    fun `deltaDisplay formats correctly`() {
        assertEquals("+1h 0m", TopGameRow(1, "g", 3600, deltaSeconds = 3600).deltaDisplay)
        assertEquals("-30m", TopGameRow(1, "g", 100, deltaSeconds = -1800).deltaDisplay)
        assertEquals("", TopGameRow(1, "g", 100, deltaSeconds = 0).deltaDisplay)
        assertEquals("NEW", TopGameRow(1, "g", 100, deltaSeconds = 100, isNew = true).deltaDisplay)
    }

    @Test
    fun `history is exactly 12 entries oldest first, zero-filled for empty months`() {
        // Only data in the current month and 6 months ago.
        stubSince(
            rollup(1L, "Halo", 600, thisMonth),
            rollup(1L, "Halo", 300, thisMonth.minusMonths(6))
        )

        val game = checkNotNull(service.getGuildView(guildId)).topGames.single()
        assertEquals(12, game.historySeconds.size)
        // Oldest first → newest last. The very last entry is the selected (current) month.
        assertEquals(600L, game.historySeconds.last())
        // Index 5 corresponds to thisMonth.minusMonths(6) when laid out oldest→newest of 12 months.
        // months = [now-11, now-10, ..., now-1, now]; now-6 is index 5.
        assertEquals(300L, game.historySeconds[5])
        // Other slots are zero-filled.
        assertEquals(0L, game.historySeconds[0])
        assertEquals(0L, game.historySeconds[10])
    }

    @Test
    fun `sparkline polyline has 12 points within the viewbox bounds`() {
        stubSince(
            rollup(1L, "Halo", 600),
            rollup(1L, "Halo", 800, thisMonth.minusMonths(1))
        )

        val game = checkNotNull(service.getGuildView(guildId)).topGames.single()
        val points = game.sparklinePolyline.split(" ").filter { it.isNotBlank() }
        assertEquals(12, points.size, "sparkline emits one point per month")
        points.forEach { p ->
            val (x, y) = p.split(",").map { it.toDouble() }
            assertTrue(x in 0.0..LeaderboardWebService.SPARK_WIDTH.toDouble(), "x must be inside viewbox")
            assertTrue(y in 0.0..LeaderboardWebService.SPARK_HEIGHT.toDouble(), "y must be inside viewbox")
        }
    }

    @Test
    fun `month picker has 12 entries oldest to newest with the current month selected by default`() {
        stubSince(rollup(1L, "Halo", 100, thisMonth.minusMonths(3)))

        val options = checkNotNull(service.getGuildView(guildId)).topGamesMonthOptions

        assertEquals(12, options.size)
        // Oldest first.
        val first = thisMonth.minusMonths(11)
        assertEquals("%04d-%02d".format(first.year, first.monthValue), options.first().value)
        assertEquals("%04d-%02d".format(thisMonth.year, thisMonth.monthValue), options.last().value)
        // Selected = current month, since no override was passed.
        assertTrue(options.last().isSelected)
        assertEquals(1, options.count { it.isSelected })
        // hasData reflects which month actually got rollups.
        val threeMonthsAgoValue = "%04d-%02d".format(thisMonth.minusMonths(3).year, thisMonth.minusMonths(3).monthValue)
        assertTrue(options.single { it.value == threeMonthsAgoValue }.hasData)
        assertFalse(options.first().hasData)
    }

    @Test
    fun `selecting a past month picks rows from that month only`() {
        val past = thisMonth.minusMonths(2)
        stubSince(
            rollup(1L, "PastGame", 999, past),
            rollup(1L, "NowGame", 5, thisMonth)
        )

        val view = checkNotNull(service.getGuildView(guildId, topGamesMonth = past))

        assertEquals(past, view.topGamesMonth)
        assertEquals("PastGame", view.topGames.single().name)
        assertEquals(999L, view.topGames.single().seconds)
        assertTrue(view.topGamesMonthOptions.single { it.isSelected }.value
            == "%04d-%02d".format(past.year, past.monthValue))
    }

    @Test
    fun `requested month outside the 12-month window falls back to current month`() {
        stubSince(rollup(1L, "Halo", 100))
        // 5 years ago — outside the retention window.
        val ancient = thisMonth.minusYears(5)

        val view = checkNotNull(service.getGuildView(guildId, topGamesMonth = ancient))

        assertEquals(thisMonth, view.topGamesMonth)
        assertNotNull(view.topGames.firstOrNull())
    }

    @Test
    fun `game name with colons and unicode round-trips intact`() {
        val tricky = "Counter-Strike: Global Offensive · 🎯"
        stubSince(rollup(1L, tricky, 100))

        val game = checkNotNull(service.getGuildView(guildId)).topGames.single()
        assertEquals(tricky, game.name)
    }
}
