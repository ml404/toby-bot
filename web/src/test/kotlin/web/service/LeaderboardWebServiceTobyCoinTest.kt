package web.service

import database.dto.MonthlyCreditSnapshotDto
import database.dto.TitleDto
import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.Instant

class LeaderboardWebServiceTobyCoinTest {

    private val guildId = 42L

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var userService: UserService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var titleService: TitleService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var service: LeaderboardWebService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Test Guild"

        service = LeaderboardWebService(
            jda = jda,
            introWebService = mockk(relaxed = true),
            moderationWebService = mockk(relaxed = true) {
                every { getLeaderboard(guildId) } returns emptyList()
            },
            userService = userService,
            marketService = marketService,
            titleService = titleService,
            snapshotService = snapshotService,
            membership = GuildMembership(jda),
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
    fun `tobyCoinLeaders shows each user's equipped title, null if none`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply {
                tobyCoins = 100L
                activeTitleId = 7L
            },
            UserDto(discordId = 2L, guildId = guildId).apply {
                tobyCoins = 50L
                activeTitleId = null
            }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { guild.getMemberById(2L) } returns member(2L, "Bob")
        every { titleService.getById(7L) } returns TitleDto(id = 7L, label = "Centurion", cost = 500L)

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals("Centurion", leaders.first { it.name == "Alice" }.title)
        assertEquals(null, leaders.first { it.name == "Bob" }.title)
    }

    @Test
    fun `tobyCoinLeaders swallows title lookup failures rather than breaking the page`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply {
                tobyCoins = 100L
                activeTitleId = 7L
            }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { titleService.getById(7L) } throws RuntimeException("title table unavailable")

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(1, leaders.size)
        assertEquals(null, leaders[0].title, "title failure falls back to null, row still renders")
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

    // ---- +/- this month ----

    @Test
    fun `coinsThisMonth equals current minus start-of-month snapshot`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 120L },  // +20 this month
            UserDto(discordId = 2L, guildId = guildId).apply { tobyCoins = 50L }   // -30 this month (sold)
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { guild.getMemberById(2L) } returns member(2L, "Bob")
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = 1L, guildId = guildId,
                snapshotDate = java.time.LocalDate.now().withDayOfMonth(1),
                socialCredit = 0L, tobyCoins = 100L),
            MonthlyCreditSnapshotDto(discordId = 2L, guildId = guildId,
                snapshotDate = java.time.LocalDate.now().withDayOfMonth(1),
                socialCredit = 0L, tobyCoins = 80L)
        )

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(20L, leaders.first { it.name == "Alice" }.coinsThisMonth)
        assertEquals(-30L, leaders.first { it.name == "Bob" }.coinsThisMonth)
    }

    @Test
    fun `coinsThisMonth is zero when no snapshot exists for that user`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 500L }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        // Lazy-write returns a baseline = current, so the first-visit delta is 0.
        every { snapshotService.upsertIfMissing(any()) } answers { firstArg() }

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(1, leaders.size)
        assertEquals(0L, leaders[0].coinsThisMonth,
            "no snapshot yet -> lazy-write baseline = current -> delta = 0 (future earns will show positive)")
        assertEquals(500L, leaders[0].coins, "current coin balance unaffected")
    }

    @Test
    fun `coinsThisMonth falls back to zero when snapshot read throws`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply { tobyCoins = 100L }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } throws
            RuntimeException("column toby_coins does not exist")
        // If the read threw, the write would throw for the same reason —
        // simulate that here so the fallback behaviour is honest.
        every { snapshotService.upsertIfMissing(any()) } throws
            RuntimeException("column toby_coins does not exist")

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(1, leaders.size, "page must still render even if the snapshot read fails")
        assertEquals(0L, leaders[0].coinsThisMonth)
    }

    @Test
    fun `buildTobyCoinLeaders lazy-writes a baseline when none exists`() {
        every { marketService.getMarket(guildId) } returns
            TobyCoinMarketDto(guildId = guildId, price = 1.0, lastTickAt = Instant.now())
        every { userService.listGuildUsers(guildId) } returns listOf(
            UserDto(discordId = 1L, guildId = guildId).apply {
                socialCredit = 500L
                tobyCoins = 40L
            }
        )
        every { guild.getMemberById(1L) } returns member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        val captured = slot<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsertIfMissing(capture(captured)) } answers { firstArg() }

        val leaders = checkNotNull(service.getGuildView(guildId)).tobyCoinLeaders

        assertEquals(0L, leaders[0].coinsThisMonth, "first visit: baseline = current, delta = 0")
        assertEquals(1L, captured.captured.discordId)
        assertEquals(40L, captured.captured.tobyCoins, "baseline must include current TOBY balance")
        assertEquals(500L, captured.captured.socialCredit,
            "baseline must also include social credit so the two leaderboards agree")
    }
}
