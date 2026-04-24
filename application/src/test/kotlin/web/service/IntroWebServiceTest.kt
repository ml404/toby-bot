package web.service

import database.dto.MusicDto
import database.dto.UserDto
import database.service.MusicFileService
import database.service.UserService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

        val result = service.getUserIntros(discordId, guildId)
        assertEquals(2, result.size)
        assertEquals(intro1.id, result[0].id)
        assertEquals(intro1.fileName, result[0].fileName)
        assertEquals(intro2.id, result[1].id)
        assertEquals(intro2.fileName, result[1].fileName)
        assertTrue(result.all { it.url == null }) // file-based intros have no URL
    }

    @Test
    fun `getUserIntros migrates legacy record where fileName is raw URL by looking up title`() {
        val youtubeUrl = "https://www.youtube.com/watch?v=_cPeDB-EQ8U"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = youtubeUrl,
            musicBlob = youtubeUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.updateMusicFile(any()) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(youtubeUrl) } returns web.service.YouTubePreview(
            videoId = "_cPeDB-EQ8U",
            title = "My Video Title",
            thumbnailUrl = null,
            durationSeconds = null
        )

        val result = spyService.getUserIntros(discordId, guildId)

        assertEquals(1, result.size)
        assertEquals("My Video Title", result[0].fileName)
        assertEquals(youtubeUrl, result[0].url)
        verify { musicFileService.updateMusicFile(match { it.fileName == "My Video Title" }) }
    }

    @Test
    fun `getUserIntros falls back to URL as display name when title lookup fails for legacy record`() {
        val youtubeUrl = "https://www.youtube.com/watch?v=_cPeDB-EQ8U"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = youtubeUrl,
            musicBlob = youtubeUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(youtubeUrl) } returns null

        val result = spyService.getUserIntros(discordId, guildId)

        assertEquals(1, result.size)
        assertEquals(youtubeUrl, result[0].fileName)
        assertEquals(youtubeUrl, result[0].url)
        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
    }

    @Test
    fun `getUserIntros exposes url field for URL-based intros`() {
        val youtubeUrl = "https://www.youtube.com/watch?v=_cPeDB-EQ8U"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "WOW. THIS IS GIVING ME MAJOR PERSONA VIBES.",
            musicBlob = youtubeUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val result = service.getUserIntros(discordId, guildId)
        assertEquals(1, result.size)
        assertEquals("WOW. THIS IS GIVING ME MAJOR PERSONA VIBES.", result[0].fileName)
        assertEquals(youtubeUrl, result[0].url)
    }

    @Test
    fun `getUserIntros extracts videoId for YouTube Shorts URL so play-clip button renders`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "My Shorts Clip",
            musicBlob = shortsUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val result = service.getUserIntros(discordId, guildId)
        assertEquals(1, result.size)
        // The view-model URL is the canonicalised watch form — the stored
        // Shorts URL is rewritten on read via the lazy-migration path.
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result[0].url)
        assertEquals("dQw4w9WgXcQ", result[0].videoId)
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/mqdefault.jpg", result[0].thumbnailUrl)
    }

    @Test
    fun `getUserIntros extracts videoId for Shorts URL with trailing slash`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ/"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "Shorts with trailing slash",
            musicBlob = shortsUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val result = service.getUserIntros(discordId, guildId)
        assertEquals(1, result.size)
        assertEquals("dQw4w9WgXcQ", result[0].videoId)
    }

    @Test
    fun `getUserIntros extracts videoId for Shorts URL carrying query params`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ?si=abc123"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "Shorts with query",
            musicBlob = shortsUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val result = service.getUserIntros(discordId, guildId)
        assertEquals(1, result.size)
        assertEquals("dQw4w9WgXcQ", result[0].videoId)
    }

    @Test
    fun `getUserIntros lazily rewrites a stored Shorts URL to the canonical watch URL`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "Pre-normalisation legacy row",
            musicBlob = shortsUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.updateMusicFile(any()) } returns intro

        val result = service.getUserIntros(discordId, guildId)

        assertEquals(expected, result[0].url)
        assertEquals(expected, String(intro.musicBlob!!))
        verify(exactly = 1) { musicFileService.updateMusicFile(match { it.id == intro.id }) }
    }

    @Test
    fun `getUserIntros rescues a corrupt row with null musicBlob by treating fileName as the source URL`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = shortsUrl,
            musicBlob = null
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.updateMusicFile(any()) } returns intro

        val result = service.getUserIntros(discordId, guildId)

        assertEquals(expected, result[0].url)
        assertEquals("dQw4w9WgXcQ", result[0].videoId)
        assertEquals(expected, String(intro.musicBlob!!))
        verify(atLeast = 1) { musicFileService.updateMusicFile(match { it.id == intro.id }) }
    }

    @Test
    fun `getUserIntros leaves a real file upload alone even when fileName looks like a URL`() {
        // Regression guard: the salvage path must never replace non-URL blob
        // bytes (a real MP3 upload) with a URL derived from the fileName.
        val fileBytes = "pretend-mp3-bytes".toByteArray()
        val urlShapedName = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = urlShapedName,
            musicBlob = fileBytes
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        service.getUserIntros(discordId, guildId)

        assertArrayEquals(fileBytes, intro.musicBlob)
        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
    }

    @Test
    fun `getUserIntros does not rewrite non-Shorts URLs`() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val intro = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "Already canonical",
            musicBlob = watchUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(intro)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val result = service.getUserIntros(discordId, guildId)

        assertEquals(watchUrl, result[0].url)
        verify(exactly = 0) { musicFileService.updateMusicFile(any()) }
    }

    // setIntroByUrl

    @Test
    fun `setIntroByUrl returns error for invalid URL`() {
        val error = service.setIntroByUrl(discordId, guildId, "not-a-url", 90, null, null, null)
        assertEquals("Invalid URL provided.", error)
    }

    @Test
    fun `setIntroByUrl creates new intro when under limit`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/audio.mp3", 90, null, null, null)
        assertNull(error)
        verify(exactly = 1) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByUrl returns error naming the slot when the same URL is already in another slot`() {
        // The pre-empt in setIntroByUrl finds the matching slot and reports
        // its index so the user knows where to replace.
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val existing = MusicDto(
            id = "${guildId}_${discordId}_2",
            index = 2,
            fileName = "Already saved",
            musicBlob = url.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(existing)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val error = service.setIntroByUrl(discordId, guildId, url, 90, null, null, null)
        assertNotNull(error)
        assertTrue(error!!.contains("slot 2"))
        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByUrl detects a duplicate even when the existing slot is still in pre-migration Shorts form`() {
        // Existing row was saved before Shorts canonicalisation, so its blob
        // is still `…/shorts/ID`. Pasting the canonical `watch?v=ID` form
        // must still be recognised as a duplicate.
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val existing = MusicDto(
            id = "${guildId}_${discordId}_1",
            index = 1,
            fileName = "Legacy Shorts row",
            musicBlob = shortsUrl.toByteArray()
        )
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(existing)
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val error = service.setIntroByUrl(discordId, guildId, watchUrl, 90, null, null, null)
        assertNotNull(error)
        assertTrue(error!!.contains("slot 1"))
        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByUrl falls back to hash-based dedupe message when existingIntros byte compare misses`() {
        // Safety net: if the user's existingIntros list happens to be empty
        // (e.g. a stale cached UserDto) but the DB hash check still catches
        // the collision, surface that instead of a spurious success.
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns null

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/audio.mp3", 90, null, null, null)
        assertNotNull(error)
        assertTrue(error!!.contains("already have this intro"))
    }

    @Test
    fun `setIntroByUrl returns error when at limit without replaceIndex`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(MusicDto(index = 1), MusicDto(index = 2), MusicDto(index = 3))
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/audio.mp3", 90, null, null, null)
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

        val error = service.setIntroByUrl(discordId, guildId, "https://example.com/new.mp3", 80, 1, null, null)
        assertNull(error)
        verify { musicFileService.updateMusicFile(match { it.fileName == "https://example.com/new.mp3" && it.introVolume == 80 }) }
    }

    // setIntroByFile

    @Test
    fun `setIntroByFile returns error for non-mp3 file`() {
        val file = MockMultipartFile("file", "audio.wav", "audio/wav", ByteArray(100))
        assertEquals("Only MP3 files are supported.", service.setIntroByFile(discordId, guildId, file, 90, null, null, null))
    }

    @Test
    fun `setIntroByFile returns error when file too large`() {
        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(IntroWebService.MAX_FILE_SIZE + 1))
        val error = service.setIntroByFile(discordId, guildId, file, 90, null, null, null)
        assertNotNull(error)
        assertTrue(error!!.contains("KB"))
    }

    @Test
    fun `setIntroByFile creates new intro`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(1000))
        assertNull(service.setIntroByFile(discordId, guildId, file, 90, null, null, null))
        verify(exactly = 1) { musicFileService.createNewMusicFile(any()) }
    }

    @Test
    fun `setIntroByFile returns error when duplicate file`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        every { musicFileService.createNewMusicFile(any()) } returns null

        val file = MockMultipartFile("file", "audio.mp3", "audio/mpeg", ByteArray(1000))
        assertEquals("This file already exists as one of your intros.", service.setIntroByFile(discordId, guildId, file, 90, null, null, null))
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

    // getYouTubeVideoTitle (back-compat)

    @Test
    fun `getYouTubeVideoTitle returns null when no YouTube video ID found`() {
        assertNull(service.getYouTubeVideoTitle("not-a-url"))
        assertNull(service.getYouTubeVideoTitle("https://example.com/audio.mp3"))
    }

    // setIntroByUrl — title stored as fileName via fetchYouTubePreview

    @Test
    fun `setIntroByUrl stores title as fileName when available`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns web.service.YouTubePreview(
            videoId = "dQw4w9WgXcQ",
            title = "My Video Title",
            thumbnailUrl = null,
            durationSeconds = null
        )

        val error = spyService.setIntroByUrl(discordId, guildId, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 90, null, null, null)

        assertNull(error)
        assertEquals("My Video Title", slot.captured.fileName)
    }

    @Test
    fun `setIntroByUrl falls back to URL as fileName when preview returns null`() {
        val url = "https://example.com/audio.mp3"
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns null

        val error = spyService.setIntroByUrl(discordId, guildId, url, 90, null, null, null)

        assertNull(error)
        assertEquals(url, slot.captured.fileName)
    }

    @Test
    fun `setIntroByUrl stores URL in musicBlob for playback`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns web.service.YouTubePreview(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            thumbnailUrl = null,
            durationSeconds = null
        )

        spyService.setIntroByUrl(discordId, guildId, url, 90, null, null, null)

        assertEquals(url, slot.captured.musicBlob?.let { String(it) })
    }

    @Test
    fun `setIntroByUrl canonicalises Shorts URLs to watch form before storing`() {
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        val expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns web.service.YouTubePreview(
            videoId = "dQw4w9WgXcQ",
            title = "A Short",
            thumbnailUrl = null,
            durationSeconds = 10
        )

        spyService.setIntroByUrl(discordId, guildId, shortsUrl, 90, null, null, null)

        assertEquals(expected, slot.captured.musicBlob?.let { String(it) })
        verify { spyService.fetchYouTubePreview(expected) }
    }

    @Test
    fun `setIntroByUrl leaves non-Shorts URLs untouched`() {
        val url = "https://example.com/audio.mp3"
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } returns mockk()
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns null

        spyService.setIntroByUrl(discordId, guildId, url, 90, null, null, null)

        assertEquals(url, slot.captured.musicBlob?.let { String(it) })
    }

    @Test
    fun `setIntroByUrl returns error when YouTube video exceeds duration limit`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { userService.getUserById(discordId, guildId) } returns user
        val spyService = spyk(service)
        every { spyService.fetchYouTubePreview(any()) } returns web.service.YouTubePreview(
            videoId = "dQw4w9WgXcQ",
            title = "Long video",
            thumbnailUrl = null,
            durationSeconds = 30
        )

        val error = spyService.setIntroByUrl(discordId, guildId, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 90, null, null, null)

        assertNotNull(error)
        assertTrue(error!!.contains("too long"))
        verify(exactly = 0) { musicFileService.createNewMusicFile(any()) }
    }

    // updateIntroVolume

    @Test
    fun `updateIntroVolume returns error when introId does not match prefix`() {
        assertEquals("Intro does not belong to you.", service.updateIntroVolume(discordId, guildId, "999_000_1", 50))
    }

    @Test
    fun `updateIntroVolume returns error when intro not found`() {
        every { musicFileService.getMusicFileById(any()) } returns null
        assertEquals("Intro not found.", service.updateIntroVolume(discordId, guildId, "${guildId}_${discordId}_1", 50))
    }

    @Test
    fun `updateIntroVolume clamps and persists`() {
        val dto = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "a.mp3", introVolume = 50)
        every { musicFileService.getMusicFileById(any()) } returns dto
        every { musicFileService.updateMusicFile(any()) } returns dto

        assertNull(service.updateIntroVolume(discordId, guildId, "${guildId}_${discordId}_1", 150))
        assertEquals(100, dto.introVolume)
    }

    // updateIntroName

    @Test
    fun `updateIntroName rejects empty name`() {
        assertEquals("Name cannot be empty.", service.updateIntroName(discordId, guildId, "${guildId}_${discordId}_1", "  "))
    }

    @Test
    fun `updateIntroName rejects ownership mismatch`() {
        assertEquals("Intro does not belong to you.", service.updateIntroName(discordId, guildId, "999_000_1", "Ok"))
    }

    @Test
    fun `updateIntroName persists trimmed name`() {
        val dto = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "old.mp3")
        every { musicFileService.getMusicFileById(any()) } returns dto
        every { musicFileService.updateMusicFile(any()) } returns dto

        assertNull(service.updateIntroName(discordId, guildId, "${guildId}_${discordId}_1", "  New Name  "))
        assertEquals("New Name", dto.fileName)
    }

    // reorderIntros

    @Test
    fun `reorderIntros rejects ids from another user`() {
        assertEquals(
            "One or more intros do not belong to you.",
            service.reorderIntros(discordId, guildId, listOf("999_000_1"))
        )
    }

    @Test
    fun `reorderIntros rejects mismatched id set`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply {
            musicDtos = mutableListOf(MusicDto(id = "${guildId}_${discordId}_1", index = 1))
        }
        every { userService.getUserById(discordId, guildId) } returns user

        val error = service.reorderIntros(discordId, guildId, listOf("${guildId}_${discordId}_2"))
        assertNotNull(error)
    }

    // validateClip

    @Test
    fun `validateClip accepts null clip when source is short enough`() {
        assertNull(service.validateClip(null, null, 10_000))
    }

    @Test
    fun `validateClip rejects null clip when source longer than cap`() {
        assertNotNull(service.validateClip(null, null, 60_000))
    }

    @Test
    fun `validateClip accepts a tight clip on a long source`() {
        assertNull(service.validateClip(10_000, 22_000, 120_000))
    }

    @Test
    fun `validateClip rejects clip longer than cap`() {
        assertNotNull(service.validateClip(0, 16_000, 60_000))
    }

    @Test
    fun `validateClip rejects end at or before start`() {
        assertNotNull(service.validateClip(5_000, 5_000, 60_000))
        assertNotNull(service.validateClip(5_000, 3_000, 60_000))
    }

    @Test
    fun `validateClip rejects end beyond source duration`() {
        assertNotNull(service.validateClip(0, 20_000, 10_000))
    }

    @Test
    fun `validateClip rejects negative start`() {
        assertNotNull(service.validateClip(-1, 1_000, 60_000))
    }

    // setIntroByUrl persists timestamps

    @Test
    fun `setIntroByUrl persists startMs and endMs on new intro`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { musicDtos = mutableListOf() }
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } answers { slot.captured }

        val err = service.setIntroByUrl(discordId, guildId, "https://example.com/a.mp3", 80, null, 3_000, 11_000)
        assertNull(err)
        assertEquals(3_000, slot.captured.startMs)
        assertEquals(11_000, slot.captured.endMs)
    }

    @Test
    fun `setIntroByFile persists startMs and endMs on new intro`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { musicDtos = mutableListOf() }
        every { userService.getUserById(discordId, guildId) } returns user
        val slot = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(slot)) } answers { slot.captured }

        val file = MockMultipartFile("file", "intro.mp3", "audio/mpeg", "bytes".toByteArray())
        val err = service.setIntroByFile(discordId, guildId, file, 80, null, 500, 6_500)

        assertNull(err)
        assertEquals(500, slot.captured.startMs)
        assertEquals(6_500, slot.captured.endMs)
    }

    // updateIntroTimestamps

    @Test
    fun `updateIntroTimestamps rejects ownership mismatch`() {
        val err = service.updateIntroTimestamps(discordId, guildId, "999_000_1", 0, 1000)
        assertEquals("Intro does not belong to you.", err)
    }

    @Test
    fun `updateIntroTimestamps rejects invalid clip`() {
        val dto = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "a.mp3")
        every { musicFileService.getMusicFileById(any()) } returns dto

        val err = service.updateIntroTimestamps(discordId, guildId, "${guildId}_${discordId}_1", 5_000, 3_000)
        assertNotNull(err)
    }

    @Test
    fun `updateIntroTimestamps persists timestamps when valid`() {
        val dto = MusicDto(id = "${guildId}_${discordId}_1", index = 1, fileName = "a.mp3")
        every { musicFileService.getMusicFileById(any()) } returns dto
        every { musicFileService.updateMusicFile(any()) } returns dto

        val err = service.updateIntroTimestamps(discordId, guildId, "${guildId}_${discordId}_1", 1_000, 8_000)
        assertNull(err)
        assertEquals(1_000, dto.startMs)
        assertEquals(8_000, dto.endMs)
    }
}

