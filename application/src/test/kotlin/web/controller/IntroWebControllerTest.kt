package web.controller

import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.service.music.MusicFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import web.service.YouTubePreview

class IntroWebControllerTest {

    private lateinit var introWebService: IntroWebService
    private lateinit var musicFileService: MusicFileService
    private lateinit var controller: IntroWebController

    private val mockUser = mockk<OAuth2User>(relaxed = true)
    private val mockClient = mockk<OAuth2AuthorizedClient>(relaxed = true)
    private val mockModel = mockk<Model>(relaxed = true)
    private val mockRa = mockk<RedirectAttributes>(relaxed = true)
    private val mockSession = mockk<HttpSession>(relaxed = true)
    private val mockRequest = mockk<HttpServletRequest>(relaxed = true).also {
        every { it.cookies } returns null
    }

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
        // Two guilds + pick=true bypasses the auto-redirect so the template
        // render path stays observable here.
        val guilds = listOf(GuildInfo("1", "Guild One", null), GuildInfo("2", "Guild Two", null))
        every { introWebService.getMutualGuilds("mock-token") } returns guilds

        val view = controller.guildList(mockClient, mockUser, pick = true, request = mockRequest, model = mockModel)

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
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any(), any(), any()) } returns null

        val view = controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 90, null, null, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("success", "Intro saved successfully.") }
    }

    @Test
    fun `setIntro with url adds error flash on failure`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any(), any(), any()) } returns "Invalid URL provided."

        val view = controller.setIntro(guildId, mockUser, "url", "bad-url", null, 90, null, null, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "Invalid URL provided.") }
    }

    @Test
    fun `setIntro with file adds success flash`() {
        val file = mockk<MultipartFile> { every { isEmpty } returns false }
        every { introWebService.setIntroByFile(any(), any(), any(), any(), any(), any(), any()) } returns null

        val view = controller.setIntro(guildId, mockUser, "file", null, file, 90, null, null, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("success", "Intro saved successfully.") }
    }

    @Test
    fun `setIntro with missing file returns error`() {
        val view = controller.setIntro(guildId, mockUser, "file", null, null, 90, null, null, null, null, mockRa)

        assertEquals("redirect:/intro/$guildId", view)
        verify { mockRa.addFlashAttribute("error", "No file provided.") }
    }

    @Test
    fun `setIntro clamps volume above 100 to 100`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any(), any(), any()) } returns null

        controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 200, null, null, null, null, mockRa)

        verify { introWebService.setIntroByUrl(any(), any(), any(), 100, any(), any(), any()) }
    }

    @Test
    fun `setIntro clamps volume below 1 to 1`() {
        every { introWebService.setIntroByUrl(any(), any(), any(), any(), any(), any(), any()) } returns null

        controller.setIntro(guildId, mockUser, "url", "https://example.com", null, 0, null, null, null, null, mockRa)

        verify { introWebService.setIntroByUrl(any(), any(), any(), 1, any(), any(), any()) }
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

    // updateTimestamps

    @Test
    fun `updateTimestamps returns ok on success`() {
        every { introWebService.updateIntroTimestamps(any(), any(), any(), any(), any()) } returns null

        val body = UpdateTimestampsRequest("${guildId}_${discordId}_1", 1000, 8000)
        val response = controller.updateTimestamps(guildId, body, mockUser, null)

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
    }

    @Test
    fun `updateTimestamps returns bad request with error`() {
        every { introWebService.updateIntroTimestamps(any(), any(), any(), any(), any()) } returns "End time must be greater than start time."

        val body = UpdateTimestampsRequest("${guildId}_${discordId}_1", 5000, 5000)
        val response = controller.updateTimestamps(guildId, body, mockUser, null)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertEquals("End time must be greater than start time.", response.body?.error)
    }

    // updateVolume / updateName / reorder

    @Test
    fun `updateVolume returns ok on success`() {
        every { introWebService.updateIntroVolume(any(), any(), any(), any()) } returns null

        val response = controller.updateVolume(
            guildId, UpdateVolumeRequest("${guildId}_${discordId}_1", 55), mockUser, null
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
        verify { introWebService.updateIntroVolume(discordId.toLong(), guildId, "${guildId}_${discordId}_1", 55) }
    }

    @Test
    fun `updateVolume surfaces the service error`() {
        every { introWebService.updateIntroVolume(any(), any(), any(), any()) } returns "Intro does not belong to you."

        val response = controller.updateVolume(
            guildId, UpdateVolumeRequest("999_999_1", 55), mockUser, null
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Intro does not belong to you.", response.body?.error)
    }

    @Test
    fun `updateName returns ok on success`() {
        every { introWebService.updateIntroName(any(), any(), any(), any()) } returns null

        val response = controller.updateName(
            guildId, UpdateNameRequest("${guildId}_${discordId}_1", "New name"), mockUser, null
        )

        assertEquals(200, response.statusCode.value())
        verify { introWebService.updateIntroName(discordId.toLong(), guildId, "${guildId}_${discordId}_1", "New name") }
    }

    @Test
    fun `updateName surfaces the service error`() {
        every { introWebService.updateIntroName(any(), any(), any(), any()) } returns "Name cannot be empty."

        val response = controller.updateName(
            guildId, UpdateNameRequest("${guildId}_${discordId}_1", "  "), mockUser, null
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Name cannot be empty.", response.body?.error)
    }

    @Test
    fun `reorder returns ok on success`() {
        every { introWebService.reorderIntros(any(), any(), any()) } returns null
        val ordered = listOf("${guildId}_${discordId}_2", "${guildId}_${discordId}_1")

        val response = controller.reorder(guildId, ReorderRequest(ordered), mockUser, null)

        assertEquals(200, response.statusCode.value())
        verify { introWebService.reorderIntros(discordId.toLong(), guildId, ordered) }
    }

    @Test
    fun `reorder surfaces the service error`() {
        every { introWebService.reorderIntros(any(), any(), any()) } returns "Reorder list does not match your intros."

        val response = controller.reorder(guildId, ReorderRequest(listOf("nope")), mockUser, null)

        assertEquals(400, response.statusCode.value())
        assertEquals("Reorder list does not match your intros.", response.body?.error)
    }

    // preview

    @Test
    fun `preview rejects a blank url`() {
        val response = controller.preview("")

        assertEquals(400, response.statusCode.value())
        assertEquals("URL is required.", response.body?.error)
    }

    @Test
    fun `preview reports a non-youtube url as unavailable rather than failing`() {
        every { introWebService.fetchYouTubePreview(any()) } returns null

        val response = controller.preview("https://example.com/a.mp3")

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
        assertEquals("Not a YouTube URL — title/duration unavailable.", response.body?.error)
    }

    @Test
    fun `preview passes a short video`() {
        every { introWebService.fetchYouTubePreview(any()) } returns YouTubePreview("abc", "Title", "thumb", 10)

        val response = controller.preview("https://youtu.be/abc")

        assertEquals(true, response.body?.ok)
        assertEquals("abc", response.body?.videoId)
        assertEquals(10, response.body?.durationSeconds)
        assertNull(response.body?.error)
    }

    @Test
    fun `preview flags a video longer than the cap but still returns its metadata`() {
        every { introWebService.fetchYouTubePreview(any()) } returns YouTubePreview("abc", "Title", "thumb", 60)

        val response = controller.preview("https://youtu.be/abc")

        assertEquals(false, response.body?.ok)
        assertEquals("abc", response.body?.videoId)
        assertNotNull(response.body?.error)
    }

    // undo-delete

    @Test
    fun `deleteIntro snapshots the intro so it can be restored`() {
        val dto = MusicDto(UserDto(discordId.toLong(), guildId), 2, "track", 40, byteArrayOf(1, 2, 3), 1_000, 5_000)
        every { musicFileService.getMusicFileById(any()) } returns dto
        every { introWebService.deleteIntro(any(), any(), any()) } returns null

        controller.deleteIntro(guildId, dto.id!!, mockUser, null, mockSession, mockRa)

        val snapshot = slot<DeletedIntroSnapshot>()
        verify { mockSession.setAttribute("undoDelete:$guildId:$discordId", capture(snapshot)) }
        assertEquals(2, snapshot.captured.index)
        assertEquals("track", snapshot.captured.fileName)
        assertEquals(1_000, snapshot.captured.startMs)
        assertEquals(5_000, snapshot.captured.endMs)
        verify { mockRa.addFlashAttribute("undoDelete", true) }
    }

    @Test
    fun `undoDelete restores the snapshot into its original slot`() {
        val user = UserDto(discordId.toLong(), guildId).apply { musicDtos = mutableListOf() }
        every { mockSession.getAttribute(any()) } returns snapshot(index = 2)
        every { introWebService.getOrCreateUser(any(), any()) } returns user

        val view = controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        val restored = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(restored)) }
        assertEquals(2, restored.captured.index)
        assertEquals("track", restored.captured.fileName)
        assertEquals(1_000, restored.captured.startMs)
        assertEquals(5_000, restored.captured.endMs)
        verify { mockRa.addFlashAttribute("success", "Intro restored.") }
        assertEquals("redirect:/intro/$guildId", view)
    }

    @Test
    fun `undoDelete reports when there is nothing to undo`() {
        every { mockSession.getAttribute(any()) } returns null

        controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { mockRa.addFlashAttribute("error", "Nothing to undo.") }
    }

    @Test
    fun `undoDelete refuses once the window has expired`() {
        every { mockSession.getAttribute(any()) } returns
            snapshot(index = 2, timestampMs = System.currentTimeMillis() - 11 * 60 * 1000)

        controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { mockRa.addFlashAttribute("error", "Undo window has expired.") }
    }

    @Test
    fun `undoDelete refuses when the slots have filled back up`() {
        val full = UserDto(discordId.toLong(), guildId).apply {
            musicDtos = (1..IntroWebService.MAX_INTRO_COUNT)
                .map { MusicDto(UserDto(discordId.toLong(), guildId), it, "x", 90, byteArrayOf(1)) }
                .toMutableList()
        }
        every { mockSession.getAttribute(any()) } returns snapshot(index = 2)
        every { introWebService.getOrCreateUser(any(), any()) } returns full

        controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
        verify { mockRa.addFlashAttribute("error", "Cannot restore — intro limit already reached.") }
    }

    @Test
    fun `undoDelete falls back to a new slot when the original was reclaimed`() {
        val user = UserDto(discordId.toLong(), guildId).apply {
            musicDtos = mutableListOf(MusicDto(UserDto(discordId.toLong(), guildId), 2, "other", 90, byteArrayOf(1)))
        }
        every { mockSession.getAttribute(any()) } returns snapshot(index = 2)
        every { introWebService.getOrCreateUser(any(), any()) } returns user

        controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        val restored = slot<MusicDto>()
        verify { musicFileService.createNewMusicFile(capture(restored)) }
        assertEquals(3, restored.captured.index)
    }

    @Test
    fun `undoDelete clears the snapshot so it cannot be replayed`() {
        every { mockSession.getAttribute(any()) } returns snapshot(index = 1)
        every { introWebService.getOrCreateUser(any(), any()) } returns
            UserDto(discordId.toLong(), guildId).apply { musicDtos = mutableListOf() }

        controller.undoDelete(guildId, mockUser, null, mockSession, mockRa)

        verify { mockSession.removeAttribute("undoDelete:$guildId:$discordId") }
    }

    // updateEnabled

    @Test
    fun `updateEnabled switches an intro out of the rotation`() {
        every { introWebService.updateIntroEnabled(any(), any(), any(), any()) } returns null

        val response = controller.updateEnabled(
            guildId, UpdateEnabledRequest("${guildId}_${discordId}_1", false), mockUser, null
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
        verify {
            introWebService.updateIntroEnabled(discordId.toLong(), guildId, "${guildId}_${discordId}_1", false)
        }
    }

    @Test
    fun `updateEnabled switches one back on`() {
        every { introWebService.updateIntroEnabled(any(), any(), any(), any()) } returns null

        controller.updateEnabled(
            guildId, UpdateEnabledRequest("${guildId}_${discordId}_1", true), mockUser, null
        )

        verify {
            introWebService.updateIntroEnabled(discordId.toLong(), guildId, "${guildId}_${discordId}_1", true)
        }
    }

    @Test
    fun `updateEnabled surfaces the service error`() {
        every { introWebService.updateIntroEnabled(any(), any(), any(), any()) } returns "Intro not found."

        val response = controller.updateEnabled(
            guildId, UpdateEnabledRequest("${guildId}_${discordId}_9", false), mockUser, null
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Intro not found.", response.body?.error)
    }

    private fun snapshot(index: Int, timestampMs: Long = System.currentTimeMillis()) = DeletedIntroSnapshot(
        id = "${guildId}_${discordId}_$index",
        index = index,
        fileName = "track",
        introVolume = 40,
        musicBlob = byteArrayOf(1, 2, 3),
        startMs = 1_000,
        endMs = 5_000,
        discordId = discordId.toLong(),
        guildId = guildId,
        timestampMs = timestampMs,
    )
}