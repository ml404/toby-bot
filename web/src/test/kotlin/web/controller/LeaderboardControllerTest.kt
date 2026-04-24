package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.LeaderboardGuildView
import web.service.LeaderboardSort
import web.service.LeaderboardWebService

/**
 * Ensures the `?sort=` query param maps to [LeaderboardSort] correctly. The
 * view must default to THIS_MONTH when the param is missing or garbage, and
 * must pass the active sort through to the service so the page state is
 * consistent with the URL.
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
        every { leaderboardWebService.getGuildView(guildId, any()) } returns emptyView()
        controller = LeaderboardController(leaderboardWebService)
    }

    private fun emptyView() = LeaderboardGuildView(
        guildName = "t", podium = emptyList(), standings = emptyList(),
        totalCreditsThisMonth = 0, totalVoiceThisMonth = 0, mostActiveMember = null,
        totalMembers = 0
    )

    private fun call(sort: String?): LeaderboardSort {
        val model: Model = mockk(relaxed = true)
        val ra: RedirectAttributes = mockk(relaxed = true)
        val sortSlot = slot<LeaderboardSort>()
        every { leaderboardWebService.getGuildView(guildId, capture(sortSlot)) } returns emptyView()
        controller.leaderboardPage(guildId, sort, user, model, ra)
        return sortSlot.captured
    }

    @Test
    fun `sort=month maps to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, call("month"))
    }

    @Test
    fun `sort=lifetime maps to LIFETIME`() {
        assertEquals(LeaderboardSort.LIFETIME, call("lifetime"))
    }

    @Test
    fun `missing sort defaults to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, call(null))
    }

    @Test
    fun `unknown sort value falls back to THIS_MONTH`() {
        assertEquals(LeaderboardSort.THIS_MONTH, call("garbage"))
    }

    @Test
    fun `service is invoked with the resolved sort`() {
        controller.leaderboardPage(guildId, "lifetime", user, mockk(relaxed = true), mockk(relaxed = true))
        verify { leaderboardWebService.getGuildView(guildId, LeaderboardSort.LIFETIME) }
    }
}
