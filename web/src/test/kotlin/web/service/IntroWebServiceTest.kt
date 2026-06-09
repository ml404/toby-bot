package web.service

import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.service.music.MusicFileService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.multipart.MultipartFile

/**
 * Unit coverage for [IntroWebService]. Exercises the validation, ownership,
 * slot-allocation and DTO-mapping logic with mocked persistence/JDA. The
 * network-bound paths (Discord guild fetch, YouTube Data API) are left to
 * integration coverage — without a `YOUTUBE_API_KEY` the preview helpers
 * short-circuit offline, which is what these tests rely on.
 */
class IntroWebServiceTest {

    private lateinit var userService: UserService
    private lateinit var musicFileService: MusicFileService
    private lateinit var jda: JDA
    private lateinit var service: IntroWebService

    private val discordId = 100L
    private val guildId = 42L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        musicFileService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        service = IntroWebService(userService, musicFileService, jda)
    }

    private fun user(vararg intros: MusicDto) = UserDto(discordId = discordId, guildId = guildId).apply {
        musicDtos = intros.toMutableList()
    }

    private fun intro(index: Int, blob: ByteArray? = null, fileName: String? = null): MusicDto {
        val u = UserDto(discordId = discordId, guildId = guildId)
        return MusicDto(u, index = index, fileName = fileName, introVolume = 50, musicBlob = blob)
    }

    // --- validateClip ----------------------------------------------------

    @Test
    fun `validateClip accepts no clip when source within cap`() {
        assertNull(service.validateClip(null, null, sourceDurationMs = null))
        assertNull(service.validateClip(null, null, sourceDurationMs = 10_000))
    }

    @Test
    fun `validateClip rejects an over-long unclipped source`() {
        val msg = service.validateClip(null, null, sourceDurationMs = 20_000)
        assertNotNull(msg)
        assertTrue(msg!!.contains("too long"), msg)
    }

    @Test
    fun `validateClip rejects negative start`() {
        assertEquals("Start time cannot be negative.", service.validateClip(-1, 5_000, null))
    }

    @Test
    fun `validateClip rejects end before or equal to start`() {
        assertEquals("End time must be greater than start time.", service.validateClip(5_000, 5_000, null))
        assertEquals("End time must be greater than start time.", service.validateClip(5_000, 1_000, null))
    }

    @Test
    fun `validateClip rejects end beyond known source duration`() {
        assertEquals("End time exceeds the source duration.", service.validateClip(0, 9_000, sourceDurationMs = 8_000))
    }

    @Test
    fun `validateClip rejects a clip span over the cap`() {
        val msg = service.validateClip(0, 16_000, null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("too long"), msg)
    }

    @Test
    fun `validateClip rejects start-only clip when remaining source exceeds cap`() {
        val msg = service.validateClip(1_000, null, sourceDurationMs = 20_000)
        assertNotNull(msg)
        assertTrue(msg!!.contains("too long"), msg)
    }

    @Test
    fun `validateClip accepts a valid bounded clip`() {
        assertNull(service.validateClip(1_000, 6_000, sourceDurationMs = 30_000))
    }

    // --- canonicaliseShortsUrl ------------------------------------------

    @Test
    fun `canonicaliseShortsUrl rewrites shorts to watch form`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            service.canonicaliseShortsUrl("https://youtube.com/shorts/abc123"),
        )
    }

    @Test
    fun `canonicaliseShortsUrl leaves non-shorts urls untouched`() {
        val url = "https://www.youtube.com/watch?v=xyz"
        assertEquals(url, service.canonicaliseShortsUrl(url))
    }

    // --- simple lookups --------------------------------------------------

    @Test
    fun `isSuperUser reflects the stored flag`() {
        every { userService.getUserById(discordId, guildId) } returns UserDto(discordId = discordId, guildId = guildId, superUser = true)
        assertTrue(service.isSuperUser(discordId, guildId))

        every { userService.getUserById(discordId, guildId) } returns UserDto(discordId = discordId, guildId = guildId, superUser = false)
        assertEquals(false, service.isSuperUser(discordId, guildId))

        every { userService.getUserById(discordId, guildId) } returns null
        assertEquals(false, service.isSuperUser(discordId, guildId))
    }

    @Test
    fun `getIntroCountsForGuilds counts each guild's intros`() {
        every { userService.getUserById(discordId, 1L) } returns user(intro(1), intro(2))
        every { userService.getUserById(discordId, 2L) } returns null
        val counts = service.getIntroCountsForGuilds(discordId, listOf(1L, 2L))
        assertEquals(2, counts[1L])
        assertEquals(0, counts[2L])
    }

    @Test
    fun `getOrCreateUser returns existing user without creating`() {
        val existing = user()
        every { userService.getUserById(discordId, guildId) } returns existing
        assertEquals(existing, service.getOrCreateUser(discordId, guildId))
        verify(exactly = 0) { userService.createNewUser(any()) }
    }

    @Test
    fun `getOrCreateUser creates a user when none exists`() {
        every { userService.getUserById(discordId, guildId) } returns null
        val created = service.getOrCreateUser(discordId, guildId)
        assertEquals(discordId, created.discordId)
        assertEquals(guildId, created.guildId)
        verify { userService.createNewUser(any()) }
    }

    @Test
    fun `getGuildName delegates to JDA`() {
        val guild = mockk<Guild> { every { name } returns "My Guild" }
        every { jda.getGuildById(guildId) } returns guild
        assertEquals("My Guild", service.getGuildName(guildId))

        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildName(guildId))
    }

    @Test
    fun `getGuildMembers excludes bots and sorts by name`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(service.getGuildMembers(guildId).isEmpty())

        val bot = member("999", "Zeta", bot = true)
        val human1 = member("1", "charlie", bot = false)
        val human2 = member("2", "Alice", bot = false)
        val guild = mockk<Guild> { every { members } returns listOf(bot, human1, human2) }
        every { jda.getGuildById(guildId) } returns guild

        val result = service.getGuildMembers(guildId)
        assertEquals(listOf("Alice", "charlie"), result.map { it.name })
    }

    private fun member(memberId: String, displayName: String, bot: Boolean): Member {
        val u = mockk<User>()
        every { u.isBot } returns bot
        val m = mockk<Member>()
        every { m.user } returns u
        every { m.id } returns memberId
        every { m.effectiveName } returns displayName
        every { m.effectiveAvatarUrl } returns "https://avatar/$memberId"
        return m
    }

    // --- getUserIntros ---------------------------------------------------

    @Test
    fun `getUserIntros returns empty for an unknown user`() {
        every { userService.getUserById(discordId, guildId) } returns null
        assertTrue(service.getUserIntros(discordId, guildId).isEmpty())
    }

    @Test
    fun `getUserIntros maps a url-backed intro and derives a thumbnail`() {
        val url = "https://www.youtube.com/watch?v=abc123"
        val dto = intro(1, blob = url.toByteArray())
        every { userService.getUserById(discordId, guildId) } returns user(dto)

        val views = service.getUserIntros(discordId, guildId)
        assertEquals(1, views.size)
        val view = views.first()
        assertEquals(url, view.url)
        assertEquals("abc123", view.videoId)
        assertEquals("https://img.youtube.com/vi/abc123/mqdefault.jpg", view.thumbnailUrl)
    }

    @Test
    fun `getUserIntros leaves a real file upload alone`() {
        val dto = intro(1, blob = byteArrayOf(1, 2, 3), fileName = "myclip.mp3")
        every { userService.getUserById(discordId, guildId) } returns user(dto)

        val view = service.getUserIntros(discordId, guildId).first()
        assertNull(view.url)
        assertEquals("myclip.mp3", view.fileName)
    }

    // --- setIntroByUrl ---------------------------------------------------

    @Test
    fun `setIntroByUrl rejects an invalid url`() {
        assertEquals("Invalid URL provided.", service.setIntroByUrl(discordId, guildId, "not a url", 50, null, null, null))
    }

    @Test
    fun `setIntroByUrl rejects adding past the cap`() {
        every { userService.getUserById(discordId, guildId) } returns user(intro(1), intro(2), intro(3))
        val msg = service.setIntroByUrl(discordId, guildId, "https://youtu.be/abc123", 50, null, null, null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("already have"), msg)
    }

    @Test
    fun `setIntroByUrl adds a new intro in the first free slot`() {
        every { userService.getUserById(discordId, guildId) } returns user(intro(1))
        every { musicFileService.createNewMusicFile(any()) } returns mockk()

        val saved = slot<MusicDto>()
        val result = service.setIntroByUrl(discordId, guildId, "https://www.youtube.com/watch?v=abc123", 70, null, null, null)
        assertNull(result)
        verify { musicFileService.createNewMusicFile(capture(saved)) }
        assertEquals(2, saved.captured.index)
    }

    @Test
    fun `setIntroByUrl flags a duplicate url already in a slot`() {
        val url = "https://www.youtube.com/watch?v=abc123"
        every { userService.getUserById(discordId, guildId) } returns user(intro(1, blob = url.toByteArray()))
        val msg = service.setIntroByUrl(discordId, guildId, url, 70, null, null, null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("slot 1"), msg)
    }

    @Test
    fun `setIntroByUrl replaces an existing slot`() {
        val existing = intro(2, blob = "https://youtu.be/old".toByteArray())
        every { userService.getUserById(discordId, guildId) } returns user(intro(1), existing)

        val result = service.setIntroByUrl(discordId, guildId, "https://www.youtube.com/watch?v=new123", 80, replaceIndex = 2, null, null)
        assertNull(result)
        verify { musicFileService.updateMusicFile(existing) }
        assertEquals(80, existing.introVolume)
    }

    // --- setIntroByFile --------------------------------------------------

    private fun mp3(name: String?, content: ByteArray, empty: Boolean = false): MultipartFile {
        val file = mockk<MultipartFile>()
        every { file.isEmpty } returns empty
        every { file.originalFilename } returns name
        every { file.size } returns content.size.toLong()
        every { file.bytes } returns content
        return file
    }

    @Test
    fun `setIntroByFile rejects an empty file`() {
        assertEquals("No file provided.", service.setIntroByFile(discordId, guildId, mp3("a.mp3", ByteArray(0), empty = true), 50, null, null, null))
    }

    @Test
    fun `setIntroByFile rejects a non-mp3 file`() {
        assertEquals("Only MP3 files are supported.", service.setIntroByFile(discordId, guildId, mp3("a.wav", byteArrayOf(1)), 50, null, null, null))
    }

    @Test
    fun `setIntroByFile rejects an oversized file`() {
        val tooBig = ByteArray(IntroWebService.MAX_FILE_SIZE + 1)
        val msg = service.setIntroByFile(discordId, guildId, mp3("a.mp3", tooBig), 50, null, null, null)
        assertNotNull(msg)
        assertTrue(msg!!.contains("maximum size"), msg)
    }

    @Test
    fun `setIntroByFile adds a new upload`() {
        every { userService.getUserById(discordId, guildId) } returns user()
        every { musicFileService.createNewMusicFile(any()) } returns mockk()
        val result = service.setIntroByFile(discordId, guildId, mp3("clip.mp3", byteArrayOf(9, 9, 9)), 60, null, null, null)
        assertNull(result)
        verify { musicFileService.createNewMusicFile(any()) }
    }

    // --- ownership-guarded mutations ------------------------------------

    private fun ownedId(index: Int) = "${guildId}_${discordId}_$index"

    @Test
    fun `deleteIntro rejects an intro owned by someone else`() {
        assertEquals("Intro does not belong to you.", service.deleteIntro(discordId, guildId, "999_888_1"))
    }

    @Test
    fun `deleteIntro reports a missing intro`() {
        every { musicFileService.getMusicFileById(ownedId(1)) } returns null
        assertEquals("Intro not found.", service.deleteIntro(discordId, guildId, ownedId(1)))
    }

    @Test
    fun `deleteIntro deletes an owned intro`() {
        every { musicFileService.getMusicFileById(ownedId(1)) } returns intro(1)
        assertNull(service.deleteIntro(discordId, guildId, ownedId(1)))
        verify { musicFileService.deleteMusicFileById(ownedId(1)) }
    }

    @Test
    fun `updateIntroVolume coerces into the legal range`() {
        val dto = intro(1)
        every { musicFileService.getMusicFileById(ownedId(1)) } returns dto
        assertNull(service.updateIntroVolume(discordId, guildId, ownedId(1), 250))
        assertEquals(100, dto.introVolume)
    }

    @Test
    fun `updateIntroName validates emptiness and length`() {
        assertEquals("Name cannot be empty.", service.updateIntroName(discordId, guildId, ownedId(1), "   "))
        assertEquals("Name is too long (max 200 characters).", service.updateIntroName(discordId, guildId, ownedId(1), "x".repeat(201)))
    }

    @Test
    fun `updateIntroName trims and persists a valid name`() {
        val dto = intro(1)
        every { musicFileService.getMusicFileById(ownedId(1)) } returns dto
        assertNull(service.updateIntroName(discordId, guildId, ownedId(1), "  Cool Intro  "))
        assertEquals("Cool Intro", dto.fileName)
    }

    @Test
    fun `updateIntroTimestamps persists a valid clip`() {
        val dto = intro(1, blob = byteArrayOf(1, 2, 3))
        every { musicFileService.getMusicFileById(ownedId(1)) } returns dto
        assertNull(service.updateIntroTimestamps(discordId, guildId, ownedId(1), 0, 5_000))
        assertEquals(0, dto.startMs)
        assertEquals(5_000, dto.endMs)
    }

    // --- reorderIntros ---------------------------------------------------

    @Test
    fun `reorderIntros rejects ids that are not yours`() {
        assertEquals(
            "One or more intros do not belong to you.",
            service.reorderIntros(discordId, guildId, listOf("999_888_1")),
        )
    }

    @Test
    fun `reorderIntros rejects a set that does not match your intros`() {
        every { userService.getUserById(discordId, guildId) } returns user(intro(1), intro(2))
        val msg = service.reorderIntros(discordId, guildId, listOf(ownedId(1)))
        assertEquals("Reorder list does not match your intros.", msg)
    }

    @Test
    fun `reorderIntros reassigns indexes via a two-phase rename`() {
        val a = intro(1); val b = intro(2)
        every { userService.getUserById(discordId, guildId) } returns user(a, b)
        every { musicFileService.getMusicFileById(a.id!!) } returns a
        every { musicFileService.getMusicFileById(b.id!!) } returns b

        // Reverse the order: b first, then a.
        val result = service.reorderIntros(discordId, guildId, listOf(b.id!!, a.id!!))
        assertNull(result)
        assertEquals(1, b.index)
        assertEquals(2, a.index)
    }

    // --- preview helpers (offline) --------------------------------------

    @Test
    fun `fetchYouTubePreview returns null for a non-video url`() {
        assertNull(service.fetchYouTubePreview("https://example.com/notavideo"))
    }

    @Test
    fun `fetchYouTubePreview degrades to a thumbnail-only preview without an api key`() {
        val preview = service.fetchYouTubePreview("https://www.youtube.com/watch?v=abc123")
        assertNotNull(preview)
        assertEquals("abc123", preview!!.videoId)
        assertNull(preview.title)
        assertEquals("https://img.youtube.com/vi/abc123/mqdefault.jpg", preview.thumbnailUrl)
    }

    // --- security hardening ----------------------------------------------

    @Test
    fun `setIntroByUrl rejects non-http schemes`() {
        // file:/ftp:/javascript: would otherwise be persisted and later fetched
        // by the music player — a local-resource read primitive.
        assertEquals("Invalid URL provided.", service.setIntroByUrl(discordId, guildId, "file:///etc/passwd", 50, null, null, null))
        assertEquals("Invalid URL provided.", service.setIntroByUrl(discordId, guildId, "ftp://internal.host/clip.mp3", 50, null, null, null))
        assertEquals("Invalid URL provided.", service.setIntroByUrl(discordId, guildId, "javascript:alert(1)", 50, null, null, null))
    }

    @Test
    fun `setIntroByUrl rejects an http url without a host`() {
        assertEquals("Invalid URL provided.", service.setIntroByUrl(discordId, guildId, "https://", 50, null, null, null))
    }

    @Test
    fun `fetchYouTubePreview rejects ids outside the YouTube id alphabet`() {
        // Quotes, parens and spaces would otherwise flow into the thumbnail
        // CSS url(...) and the Data API query string.
        assertNull(service.fetchYouTubePreview("https://www.youtube.com/watch?v=abc')injected"))
        assertNull(service.fetchYouTubePreview("https://www.youtube.com/watch?v=abc def"))
    }

    @Test
    fun `sanitizeUploadFileName strips directory components and control characters`() {
        assertEquals("clip.mp3", service.sanitizeUploadFileName("../../../etc/clip.mp3"))
        assertEquals("clip.mp3", service.sanitizeUploadFileName("C:\\Users\\victim\\clip.mp3"))
        assertEquals("clip.mp3", service.sanitizeUploadFileName("cl ip.mp3"))
        assertNull(service.sanitizeUploadFileName(null))
        assertNull(service.sanitizeUploadFileName("   "))
    }

    @Test
    fun `sanitizeUploadFileName collapses a url-shaped name so it cannot feed the blob-heal path`() {
        assertEquals("evil.mp3", service.sanitizeUploadFileName("https://evil.example/evil.mp3"))
    }

    @Test
    fun `setIntroByFile persists the sanitized file name`() {
        every { userService.getUserById(discordId, guildId) } returns user()
        val saved = slot<MusicDto>()
        every { musicFileService.createNewMusicFile(capture(saved)) } returns mockk()
        val result = service.setIntroByFile(discordId, guildId, mp3("..\\..\\traversal.mp3", byteArrayOf(1)), 60, null, null, null)
        assertNull(result)
        assertEquals("traversal.mp3", saved.captured.fileName)
    }
}
