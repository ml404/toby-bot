package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
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
import web.service.GuildInfo
import web.service.TeamWebService

class TeamWebControllerTest {

    private lateinit var teamWebService: TeamWebService
    private lateinit var controller: TeamWebController

    private val mockUser = mockk<OAuth2User>(relaxed = true)
    private val mockClient = mockk<OAuth2AuthorizedClient>(relaxed = true)
    private val mockModel = mockk<Model>(relaxed = true)
    private val mockRa = mockk<RedirectAttributes>(relaxed = true)

    private val discordId = "111"
    private val guildId = 222L

    @BeforeEach
    fun setup() {
        teamWebService = mockk(relaxed = true)
        controller = TeamWebController(teamWebService, "test-client-id")

        every { mockUser.getAttribute<String>("id") } returns discordId
        every { mockUser.getAttribute<String>("username") } returns "TestUser"
        every { mockClient.accessToken } returns mockk<OAuth2AccessToken> {
            every { tokenValue } returns "mock-token"
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `guildList renders team-guilds template`() {
        val guilds = listOf(GuildInfo("1", "Guild One", null))
        every { teamWebService.getMutualGuilds("mock-token") } returns guilds

        val view = controller.guildList(mockClient, mockUser, mockModel)

        assertEquals("team-guilds", view)
        verify { mockModel.addAttribute("guilds", guilds) }
        verify { mockModel.addAttribute("username", "TestUser") }
    }

    @Test
    fun `page redirects anonymous users to guild picker`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.page(guildId, mockUser, mockModel, mockRa)

        assertEquals("redirect:/teams/guilds", view)
    }

    @Test
    fun `page redirects non-members with a flash error`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns false

        val view = controller.page(guildId, mockUser, mockModel, mockRa)

        assertEquals("redirect:/teams/guilds", view)
    }

    @Test
    fun `page renders teams template for guild members`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns true
        every { teamWebService.getGuildName(guildId) } returns "Test Guild"

        val view = controller.page(guildId, mockUser, mockModel, mockRa)

        assertEquals("teams", view)
        verify { mockModel.addAttribute("guildName", "Test Guild") }
    }

    @Test
    fun `createPreset surfaces validation errors as flash messages`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns true
        every { teamWebService.upsertPreset(guildId, "x", any(), discordId.toLong()) } returns "Pick at least one member."

        val view = controller.createPreset(guildId, "x", null, mockUser, mockRa)

        assertTrue(view.startsWith("redirect:/teams/$guildId"))
        verify { mockRa.addFlashAttribute("error", "Pick at least one member.") }
    }

    @Test
    fun `createPreset adds a success flash on save`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns true
        every { teamWebService.upsertPreset(guildId, "Crew", listOf("111", "222"), discordId.toLong()) } returns null

        controller.createPreset(guildId, "Crew", listOf("111", "222"), mockUser, mockRa)

        verify { mockRa.addFlashAttribute("success", "Saved preset 'Crew'.") }
    }

    @Test
    fun `deletePreset returns 403 to non-members`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns false

        val response = controller.deletePreset(guildId, 7L, mockUser)

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `deletePreset returns ok on success`() {
        every { teamWebService.isMember(discordId.toLong(), guildId) } returns true
        every { teamWebService.deletePreset(7L, guildId) } returns null

        val response = controller.deletePreset(guildId, 7L, mockUser)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }
}
