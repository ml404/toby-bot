package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.LeaderboardGuildView
import web.service.LeaderboardSort
import web.service.LeaderboardWebService
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Ensures the `?sort=` and `?month=` query params map to the right service
 * arguments. The view must default to THIS_MONTH when sort is missing/garbage
 * and pass any valid month through; invalid/out-of-range months must fall
 * back to null so the service can pick the default.
 */
class LeaderboardControllerTest {

    private val guildId = 77L
    private val discordId = 100L

    private lateinit var leaderboardWebService: LeaderboardWebService
    private lateinit var user: OAuth2User
    private lateinit var controller: LeaderboardController

    @BeforeEach
    fun setup() {
        leaderboardWebService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { leaderboardWebService.isMember(discordId, guildId) } returns true
        every { leaderboardWebService.getGuildView(guildId, any(), any()) } returns emptyView()
        controller = LeaderboardController(leaderboardWebService)
    }

    private fun emptyView() = LeaderboardGuildView(
        guildName = "t", podium = emptyList(), standings = emptyList(),
        totalCreditsThisMonth = 0, totalVoiceThisMonth = 0, mostActiveMember = null,
        totalMembers = 0
    )

    private fun callSort(sort: String?): LeaderboardSort {
        val model: Model = mockk(relaxed = true)
        val ra: RedirectAttributes = mockk(relaxed = true)
        val sortSlot = slot<LeaderboardSort>()
        every { leaderboardWebService.getGuildView(guildId, capture(sortSlot), any()) } returns emptyView()
        controller.leaderboardPage(guildId, sort, null, user, model, ra)
        return sortSlot.captured
    }

    private fun callMonth(month: String?): LocalDate? {
        val model: Model = mockk(relaxed = true)
        val ra: RedirectAttributes = mockk(relaxed = true)
        var captured: LocalDate? = null
        every { leaderboardWebService.getGuildView(guildId, any(), any()) } answers {
            captured = thirdArg()
            emptyView()
        }
        controller.leaderboardPage(guildId, null, month, user, model, ra)
        return captured
    }

    @Test
    fun `sort=month maps to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, callSort("month"))
    }

    @Test
    fun `sort=lifetime maps to LIFETIME`() {
        assertEquals(LeaderboardSort.LIFETIME, callSort("lifetime"))
    }

    @Test
    fun `missing sort defaults to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, callSort(null))
    }

    @Test
    fun `unknown sort value falls back to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, callSort("garbage"))
    }

    @Test
    fun `service is invoked with the resolved sort`() {
        controller.leaderboardPage(guildId, "lifetime", null, user, mockk(relaxed = true), mockk(relaxed = true))
        verify { leaderboardWebService.getGuildView(guildId, LeaderboardSort.LIFETIME, null) }
    }

    @Test
    fun `valid month within last 12 months parses through to the service`() {
        val target = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(3)
        val value = "%04d-%02d".format(target.year, target.monthValue)
        assertEquals(target, callMonth(value))
    }

    @Test
    fun `current month parses through`() {
        val now = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val value = "%04d-%02d".format(now.year, now.monthValue)
        assertEquals(now, callMonth(value))
    }

    @Test
    fun `null month falls back to null`() {
        assertNull(callMonth(null))
    }

    @Test
    fun `blank month falls back to null`() {
        assertNull(callMonth(""))
    }

    @Test
    fun `unparseable month falls back to null`() {
        assertNull(callMonth("not-a-month"))
        assertNull(callMonth("2026-13"))
        assertNull(callMonth("2026-00"))
        assertNull(callMonth("2026"))
    }

    @Test
    fun `out-of-range month falls back to null`() {
        // Older than 11 months ago
        assertNull(callMonth("2020-01"))
        // Future
        val future = LocalDate.now(ZoneOffset.UTC).plusMonths(2)
        val futureValue = "%04d-%02d".format(future.year, future.monthValue)
        assertNull(callMonth(futureValue))
    }

    @Test
    fun `parseMonth helper is robust on edge cases`() {
        val c = LeaderboardController(leaderboardWebService)
        assertNull(c.parseMonth(null))
        assertNull(c.parseMonth(""))
        assertNull(c.parseMonth("   "))
        assertNull(c.parseMonth("2026-02-15")) // day-included form not accepted
        assertNull(c.parseMonth("2026/02"))
        val target = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1)
        val value = "%04d-%02d".format(target.year, target.monthValue)
        assertEquals(target, c.parseMonth(value))
    }

}
