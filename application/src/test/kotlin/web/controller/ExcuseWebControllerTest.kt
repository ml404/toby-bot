package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.ExcusePageViewModel
import web.service.ExcuseWebService
import web.service.GuildInfo
import web.service.MemberInfo
import web.service.RandomExcuseViewModel
import web.service.SubmitResult

class ExcuseWebControllerTest {

    private lateinit var excuseWebService: ExcuseWebService
    private lateinit var controller: ExcuseWebController

    private val mockUser = mockk<OAuth2User>(relaxed = true)
    private val mockClient = mockk<OAuth2AuthorizedClient>(relaxed = true)
    private val mockModel = mockk<Model>(relaxed = true)
    private val mockRa = mockk<RedirectAttributes>(relaxed = true)
    private val mockRequest = mockk<HttpServletRequest>(relaxed = true).also {
        every { it.cookies } returns null
    }

    private val discordId = "111"
    private val guildId = 222L

    @BeforeEach
    fun setup() {
        excuseWebService = mockk(relaxed = true)
        controller = ExcuseWebController(excuseWebService, "test-client-id")

        every { mockUser.getAttribute<String>("id") } returns discordId
        every { mockUser.getAttribute<String>("username") } returns "TestUser"
        every { mockClient.accessToken } returns mockk<OAuth2AccessToken> {
            every { tokenValue } returns "mock-token"
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // guildList

    @Test
    fun `guildList renders the excuse-guilds template with counts`() {
        // Two guilds + pick=true bypasses the auto-redirect so we can observe
        // the full template render path (with the new pick / request params).
        val guilds = listOf(GuildInfo("1", "Guild One", null), GuildInfo("2", "Guild Two", null))
        every { excuseWebService.getMutualGuilds("mock-token") } returns guilds
        every { excuseWebService.getApprovedCountsForGuilds(listOf(1L, 2L)) } returns mapOf(1L to 5)

        val view = controller.guildList(mockClient, mockUser, pick = true, request = mockRequest, model = mockModel)

        assertEquals("excuse-guilds", view)
        verify { mockModel.addAttribute("guilds", guilds) }
        verify { mockModel.addAttribute("approvedCounts", mapOf("1" to 5)) }
        verify { mockModel.addAttribute("username", "TestUser") }
    }

    // page

    @Test
    fun `page redirects to picker when discordId is null`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.page(guildId, "approved", null, 1, mockUser, mockModel, mockRa)

        assertEquals("redirect:/excuses/guilds", view)
    }

    @Test
    fun `page redirects when guild not found`() {
        every { excuseWebService.getGuildName(guildId) } returns null

        val view = controller.page(guildId, "approved", null, 1, mockUser, mockModel, mockRa)

        assertEquals("redirect:/excuses/guilds", view)
        verify { mockRa.addFlashAttribute("error", "Bot is not in that server.") }
    }

    @Test
    fun `page returns excuses view and populates model`() {
        val pageVm = ExcusePageViewModel(
            rows = emptyList(),
            page = 1,
            totalPages = 1,
            totalCount = 0L,
            isSuperUser = true,
            requestedTab = "approved",
            query = null,
        )
        every { excuseWebService.getGuildName(guildId) } returns "Test Guild"
        every { excuseWebService.getPage(guildId, "approved", null, 1, 111L) } returns pageVm
        every { excuseWebService.getGuildMembers(guildId) } returns listOf(MemberInfo("1", "Alice", null))

        val view = controller.page(guildId, "approved", null, 1, mockUser, mockModel, mockRa)

        assertEquals("excuses", view)
        verify { mockModel.addAttribute("guildName", "Test Guild") }
        verify { mockModel.addAttribute("isSuperUser", true) }
        verify { mockModel.addAttribute("members", listOf(MemberInfo("1", "Alice", null))) }
        verify { mockModel.addAttribute("totalPages", 1) }
    }

    @Test
    fun `page skips member lookup when not superuser`() {
        val pageVm = ExcusePageViewModel(
            rows = emptyList(),
            page = 1,
            totalPages = 1,
            totalCount = 0L,
            isSuperUser = false,
            requestedTab = "approved",
            query = null,
        )
        every { excuseWebService.getGuildName(guildId) } returns "Test Guild"
        every { excuseWebService.getPage(guildId, "approved", null, 1, 111L) } returns pageVm

        controller.page(guildId, "approved", null, 1, mockUser, mockModel, mockRa)

        verify { mockModel.addAttribute("members", emptyList<MemberInfo>()) }
    }

    // submit

    @Test
    fun `submit adds success flash on success and preserves tab+query`() {
        every { excuseWebService.submit(guildId, "an excuse", null, 111L) } returns SubmitResult(id = 7L)

        val view = controller.submit(guildId, "an excuse", null, "approved", "rain", mockUser, mockRa)

        assertEquals("redirect:/excuses/$guildId?tab=approved&q=rain", view)
        verify { mockRa.addFlashAttribute("success", "Submitted for approval (id #7).") }
    }

    @Test
    fun `submit adds error flash on failure`() {
        every { excuseWebService.submit(any(), any(), any(), any()) } returns SubmitResult(error = "nope")

        val view = controller.submit(guildId, "x", null, null, null, mockUser, mockRa)

        assertEquals("redirect:/excuses/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "nope") }
    }

    // approve

    @Test
    fun `approve returns ok on success`() {
        every { excuseWebService.approve(5L, 111L, guildId) } returns null

        val response = controller.approve(guildId, 5L, mockUser)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body?.ok == true)
    }

    @Test
    fun `approve returns 400 with error message on failure`() {
        every { excuseWebService.approve(5L, 111L, guildId) } returns "boom"

        val response = controller.approve(guildId, 5L, mockUser)

        assertEquals(400, response.statusCode.value())
        assertEquals("boom", response.body?.error)
    }

    @Test
    fun `approve returns 401 when not signed in`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val response = controller.approve(guildId, 5L, mockUser)

        assertEquals(401, response.statusCode.value())
    }

    // delete

    @Test
    fun `delete returns ok on success`() {
        every { excuseWebService.delete(5L, 111L, guildId) } returns null

        val response = controller.delete(guildId, 5L, mockUser)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body?.ok == true)
    }

    @Test
    fun `delete returns 400 on permission failure`() {
        every { excuseWebService.delete(5L, 111L, guildId) } returns "denied"

        val response = controller.delete(guildId, 5L, mockUser)

        assertEquals(400, response.statusCode.value())
        assertEquals("denied", response.body?.error)
    }

    // random

    @Test
    fun `random returns the pick`() {
        every { excuseWebService.getRandomApproved(guildId) } returns
            RandomExcuseViewModel(id = 9L, text = "ate it", author = "dog")

        val response = controller.random(guildId, mockUser)

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
        assertEquals(9L, response.body?.id)
        assertEquals("ate it", response.body?.text)
    }

    @Test
    fun `random returns ok=false with message when nothing approved yet`() {
        every { excuseWebService.getRandomApproved(guildId) } returns null

        val response = controller.random(guildId, mockUser)

        assertEquals(200, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertEquals("There are no approved excuses yet.", response.body?.error)
    }
}
