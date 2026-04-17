package web.controller

import database.service.MusicFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.GuildInfo
import web.service.IntroWebService

class IntroWebControllerTest {

    private lateinit var introWebService: IntroWebService
    private lateinit var musicFileService: MusicFileService
    private lateinit var controller: IntroWebController

    private val mockUser = mockk<OAuth2User>(relaxed = true)
    private val mockClient = mockk<OAuth2AuthorizedClient>(relaxed = true)
    private val mockModel = mockk<Model>(relaxed = true)
    private val mockRa = mockk<RedirectAttributes>(relaxed = true)
    private val mockSession = mockk<HttpSession>(relaxed = true)

    private val discordId = "111"
    private val guildId = 222L

    @BeforeEach
    fun setup() {
        introWebService = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        controller = IntroWebController(introWebService, musicFileService, "test-client-id")

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
    fun `guildList adds guilds and username to model`() {
        val guilds = listOf(GuildInfo("1", "Guild One", null))
        every { introWebService.getMutualGuilds("mock-token") } returns guilds

        val view = controller.guildList(mockClient, mockUser, mockModel)

        assertEquals("guilds", view)
        verify { mockModel.addAttribute("guilds", guilds) }
        verify { mockModel.addAttribute("username", "TestUser") }
    }

    // introPage

    @Test
    fun `introPage redirects to guilds when discordId is null`() {
        every { mockUser.getAttribute<String>("id") } returns null

        val view = controller.introPage(guildId, null, mockUser, mockModel, mockRa)

        assertEquals("redirect:/intro/guilds", view)
    }

    @Test
    fun `introPage redirects when guild not found`() {
        every { introWebService.getGuildName(guildId) } returns null

        val view = controller.introPage(guildId, null, mockUser, mockModel, mockRa)

        assertEquals("redirect:/intro/guilds", view)
        verify { mockRa.addFlashAttribute("error", "Bot is not in that server.") }
    }

    @Test
    fun `introPage returns intros view with model attributes`() {
        val intros = listOf(web.service.IntroViewModel(id = "1_2_1", index = 1, fileName = "a.mp3", introVolume = 90, url = null))
        every { introWebService.getGuildName(guildId) } returns "Test Guild"
        every { introWebService.getUserIntros(discordId.toLong(), guildId) } returns intros

        val view = controller.introPage(guildId, null, mockUser, mockModel, mockRa)

        assertEquals("intros", view)
        verify { mockModel.addAttribute("intros", intros) }
        verify { mockModel.addAttribute("guildName", "Test Guild") }
        verify { mockModel.addAttribute("atLimit", false) }
        verify { mockModel.addAttribute("maxIntros", IntroWebService.MAX_INTRO_COUNT) }
    }

    // setIntro

    @Test
    fun `setIntro with url adds success flash on success`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any()) } returns null

        val view = controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 90, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("success", "Intro saved successfully.") }
    }

    @Test
    fun `setIntro with url adds error flash on failure`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any()) } returns "Invalid URL provided."

        val view = controller.setIntro(guildId, mockUser, "url", "bad-url", null, 90, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "Invalid URL provided.") }
    }

    @Test
    fun `setIntro with file adds success flash`() {
        val file = mockk<MultipartFile> { every { isEmpty } returns false }
        every { introWebService.setIntroByFile(any(), any(), any(), any(), any()) } returns null

        val view = controller.setIntro(guildId, mockUser, "file", null, file, 90, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("success", "Intro saved successfully.") }
    }

    @Test
    fun `setIntro with missing file returns error`() {
        val view = controller.setIntro(guildId, mockUser, "file", null, null, 90, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "No file provided.") }
    }

    @Test
    fun `setIntro clamps volume above 100 to 100`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any()) } returns null

        controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 200, null, null, mockRa)

        verify { introWebService.setIntroByUrl(any(), any(), any(), 100, any()) }
    }

    @Test
    fun `setIntro clamps volume below 1 to 1`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any()) } returns null

        controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 0, null, null, mockRa)

        verify { introWebService.setIntroByUrl(any(), any(), any(), 1, any()) }
    }

    // deleteIntro

    @Test
    fun `deleteIntro adds success flash on success`() {
        every { introWebService.deleteIntro(any(), any(), any()) } returns null

        val view = controller.deleteIntro(guildId, "${guildId}_${discordId}_1", mockUser, null, mockSession, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("success", "Intro deleted.") }
    }

    @Test
    fun `deleteIntro adds error flash on failure`() {
        every { introWebService.deleteIntro(any(), any(), any()) } returns "Intro not found."

        val view = controller.deleteIntro(guildId, "${guildId}_${discordId}_1", mockUser, null, mockSession, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "Intro not found.") }
    }
}
