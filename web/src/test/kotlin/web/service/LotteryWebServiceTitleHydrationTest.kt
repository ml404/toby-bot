package web.service

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.dto.TitleDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.TitleService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Title-hydration coverage for [LotteryWebService.snapshot]. The
 * weighted-lottery top-holder list is the lottery page's rich-info
 * surface; failing to thread `activeTitleId → TitleService.getById ->
 * label` would silently drop the title pill from the rendered UI even
 * when a user has a purchased title equipped.
 *
 * Mirrors the defensive pattern from [LeaderboardWebService.buildTobyCoinLeaders]:
 * a missing / stale title id must surface as `title = null` (no pill),
 * never an exception that nukes the snapshot.
 */
class LotteryWebServiceTitleHydrationTest {

    private val guildId = 100L
    private val viewerId = 1L

    private val jackpotLotteryService: JackpotLotteryService = mockk(relaxed = true)
    private val configService: ConfigService = mockk(relaxed = true)
    private val memberLookupHelper: MemberLookupHelper = mockk(relaxed = true)
    private val userService: UserService = mockk(relaxed = true)
    private val titleService: TitleService = mockk(relaxed = true)

    private val service = LotteryWebService(
        jackpotLotteryService,
        configService,
        memberLookupHelper,
        userService,
        titleService,
    )

    private fun stubWeightedOpenWithTickets(
        tickets: List<JackpotLotteryTicketDto>,
    ) {
        val weightedOpen = JackpotLotteryDto(
            id = 1L,
            guildId = guildId,
            ticketPrice = 50L,
            poolAmount = 1_000L,
            winnerCount = 3,
            openedAt = Instant.now(),
            closesAt = Instant.now().plusSeconds(3600),
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns weightedOpen
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns tickets
        // No-op stubs so resolveAll doesn't throw when the test doesn't care.
        every { memberLookupHelper.resolveAll(guildId, any()) } returns emptyMap()
        every { memberLookupHelper.fallbackName(any()) } answers { "Player ${firstArg<Long>()}" }
    }

    @Test
    fun `top holder with active title gets the title label`() {
        val ticket = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 7L, ticketCount = 5, spent = 250L,
        )
        stubWeightedOpenWithTickets(listOf(ticket))

        val user = UserDto(discordId = 7L, guildId = guildId, activeTitleId = 42L)
        every { userService.getUserById(7L, guildId) } returns user
        every { titleService.getById(42L) } returns TitleDto(id = 42L, label = "Lucky Roller")

        val snap = service.snapshot(guildId, viewerId)

        assertEquals(1, snap.weightedTopHolders.size)
        assertEquals(7L, snap.weightedTopHolders[0].discordId)
        assertEquals("Lucky Roller", snap.weightedTopHolders[0].title)
    }

    @Test
    fun `top holder with no active title surfaces null title`() {
        val ticket = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 8L, ticketCount = 3, spent = 150L,
        )
        stubWeightedOpenWithTickets(listOf(ticket))

        every { userService.getUserById(8L, guildId) } returns
            UserDto(discordId = 8L, guildId = guildId, activeTitleId = null)

        val snap = service.snapshot(guildId, viewerId)

        assertEquals(1, snap.weightedTopHolders.size)
        assertNull(snap.weightedTopHolders[0].title)
    }

    @Test
    fun `unresolvable title id swallowed - no exception, no pill`() {
        val ticket = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 9L, ticketCount = 1, spent = 50L,
        )
        stubWeightedOpenWithTickets(listOf(ticket))

        every { userService.getUserById(9L, guildId) } returns
            UserDto(discordId = 9L, guildId = guildId, activeTitleId = 99L)
        // Title id refers to a deleted / stale row.
        every { titleService.getById(99L) } returns null

        val snap = service.snapshot(guildId, viewerId)

        assertEquals(1, snap.weightedTopHolders.size)
        assertNull(snap.weightedTopHolders[0].title)
    }

    @Test
    fun `userService throwing does not break the snapshot`() {
        val ticket = JackpotLotteryTicketDto(
            lotteryId = 1L, discordId = 10L, ticketCount = 2, spent = 100L,
        )
        stubWeightedOpenWithTickets(listOf(ticket))
        every { userService.getUserById(10L, guildId) } throws RuntimeException("db down")

        val snap = service.snapshot(guildId, viewerId)

        // Snapshot still renders — title is null, but the holder is present.
        assertEquals(1, snap.weightedTopHolders.size)
        assertNull(snap.weightedTopHolders[0].title)
    }
}
