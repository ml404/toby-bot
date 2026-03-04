package web.service

import database.dto.MusicDto
import database.dto.UserDto
import database.service.MusicFileService
import database.service.UserService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

class IntroWebServiceTest {

    private lateinit var userService: UserService
    private lateinit var musicFileService: MusicFileService
    private lateinit var jda: JDA
    private lateinit var service: IntroWebService

    private val discordId = 111L
    private val guildId = 222L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        service = IntroWebService(userService, musicFileService, jda)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // getGuildName

    @Test
    fun `getGuildName returns name when guild exists`() {
        every { jda.getGuildById(guildId) } returns mockk<Guild> { every { name } returns "Test Guild" }
        assertEquals("Test Guild", service.getGuildName(guildId))
    }

    @Test
    fun `getGuildName returns null when guild not found`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildName(guildId))
    }

    // getOrCreateUser

    @Test
    fun `getOrCreateUser returns existing user`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user

        assertEquals(user, service.getOrCreateUser(discordId, guildId))
        verify(exactly = 0) { userService.createNewUser(any()) }
    }

    @Test
    fun `getOrCreateUser creates user when not found`() {
        every { userService.getUserById(discordId, guildId) } returns null
        every { userService.createNewUser(any()) } returns UserDto(discordId = discordId, guildId = guildId)

        val result = service.getOrCreateUser(discordId, guildId)
        assertEquals(discordId, result.discordId)
        verify(exactly = 1) { userService.createNewUser(any()) }
    }

    // getUserIntros

    @Test
    fun `getUserIntros returns empty list when user not found`() {
        every { userService.getUserById(discordId, guildId) } returns null
        assertTrue(service.getUserIntros(discordId, guildId).isEmpty())
    }

    @Test
    fun `getUserIntros returns intros sorted by index`() {
        val intro2 = MusicDto(id = "${guildId}_${discordId}_2", index = 2, fileName = "b.mp3")
        val intro1 = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "a.mp3")
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro2, intro1)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        assertEquals(listOf(intro1, intro2), service.getUserIntros(discordId, guildId))
    }

    // setIntroByUrl

    @Test
    fun `setIntroByUrl returns error for invalid URL`() {
        val error = service.setIntroByUrl(discordId, guildId, "not-a-url", 90, null)
        assertEquals("Invalid URL provided.", error)
    }

    @Test
    fun `setIntroByUrl creates new intro when under limit`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/audio.mp3", 90, null)
        assertNull(error)
        verify(exactly = 1) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByUrl returns error when at limit without replaceIndex`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(MusicDto(index = 1), MusicDto(index = 2), MusicDto(index = 3))
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/audio.mp3", 90, null)
        assertNotNull(error)
        assertTrue(error!!.contains("${IntroWebService.MAX_INTRO_COUNT}"))
    }

    @Test
    fun `setIntroByUrl replaces existing intro when replaceIndex provided`() {
        val intro = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "old.mp3")
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro, MusicDto(index = 2), MusicDto(index = 3))
        }
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.updateMusicFile(any()) } returns mockk()

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/new.mp3", 80, 1)
        assertNull(error)
        verify { musicFileService.updateMusicFile(match { it.fileName == "https://example.com/new.mp3" && it.introVolume == 80 }) }
    }

    // setIntroByFile

    @Test
    fun `setIntroByFile returns error for non-mp3 file`() {
        val file = MockMultipartFile("file", "audio.wav", "audio/wav", ByteArray(100))
        assertEquals("Only MP3 files are supported.", service.setIntroByFile(discordId, guildId, file, 90, null))
    }

    @Test
    fun `setIntroByFile returns error when file too large`() {
        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(IntroWebService.MAX_FILE_SIZE + 1))
        val error = service.setIntroByFile(discordId, guildId, file, 90, null)
        assertNotNull(error)
        assertTrue(error!!.contains("KB"))
    }

    @Test
    fun `setIntroByFile creates new intro`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(1000))
        assertNull(service.setIntroByFile(discordId, guildId, file, 90, null))
        verify(exactly = 1) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByFile returns error when duplicate file`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns null

        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(1000))
        assertEquals("This file already exists as one of your intros.", service.setIntroByFile(discordId, guildId, file, 90, null))
    }

    // deleteIntro

    @Test
    fun `deleteIntro returns error when introId does not match prefix`() {
        assertEquals("Intro does not belong to you.", service.deleteIntro(discordId, guildId, "999_000_1"))
    }

    @Test
    fun `deleteIntro returns error when intro not found`() {
        every { musicFileService.getMusicFileById(any()) } returns null
        assertEquals("Intro not found.", service.deleteIntro(discordId, guildId, "${guildId}_${discordId}_1"))
    }

    @Test
    fun `deleteIntro deletes successfully`() {
        every { musicFileService.getMusicFileById(any()) } returns mockk()
        every { musicFileService.deleteMusicFileById(any()) } just Runs

        assertNull(service.deleteIntro(discordId, guildId, "${guildId}_${discordId}_1"))
        verify { musicFileService.deleteMusicFileById("${guildId}_${discordId}_1") }
    }
}
