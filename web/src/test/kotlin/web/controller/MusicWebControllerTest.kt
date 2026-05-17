package web.controller

import core.music.MusicControlGateway
import core.music.MusicControlGateway.LoadResult
import core.music.MusicControlGateway.TrackInfo
import database.service.MusicPlaylistService.PlaylistNameTakenException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.MusicSseService
import web.service.MusicWebService

class MusicWebControllerTest {

    private val guildId = 100L
    private val discordId = 200L
    private val otherDiscordId = 999L

    private lateinit var musicWebService: MusicWebService
    private lateinit var sseService: MusicSseService
    private lateinit var user: OAuth2User
    private lateinit var anonUser: OAuth2User
    private lateinit var nonMemberUser: OAuth2User
    private lateinit var controller: MusicWebController

    @BeforeEach
    fun setUp() {
        musicWebService = mockk(relaxed = true)
        sseService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        nonMemberUser = mockk {
            every { getAttribute<String>("id") } returns otherDiscordId.toString()
            every { getAttribute<String>("username") } returns "stranger"
        }
        anonUser = mockk {
            every { getAttribute<String>("id") } returns null
            every { getAttribute<String>("username") } returns null
        }
        every { musicWebService.isMember(discordId, guildId) } returns true
        every { musicWebService.isMember(otherDiscordId, guildId) } returns false
        // Members default to musicPermission=true (matches the slash-command
        // gate); revoked-permission cases stub this explicitly.
        every { musicWebService.canControlMusic(discordId, guildId) } returns true
        every { musicWebService.canControlMusic(otherDiscordId, guildId) } returns false
        controller = MusicWebController(musicWebService, sseService)
    }

    // ---- Auth / authz --------------------------------------------------

    @Test
    fun `pause unauthenticated returns 401`() {
        val response = controller.pause(guildId, null)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `pause non-member returns 403`() {
        val response = controller.pause(guildId, nonMemberUser)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `pause member returns 200`() {
        every { musicWebService.pause(guildId) } returns true
        val response = controller.pause(guildId, user)
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }

    // ---- Permission gate — every write endpoint must reject a member
    //      whose musicPermission has been revoked. Holds the wiring honest
    //      so a future endpoint plumbed through `guarded` instead of
    //      `guardedWrite` is caught here instead of in prod. -------------

    @Test
    fun `every write endpoint returns 403 when musicPermission is revoked`() {
        every { musicWebService.canControlMusic(discordId, guildId) } returns false
        // Each entry is a (label, invocation) pair so a failure points at the
        // specific endpoint that escaped the gate.
        val writes: List<Pair<String, () -> Int>> = listOf(
            "load"             to { controller.load(guildId, user, MusicWebController.LoadRequest("x")).statusCode.value() },
            "pause"            to { controller.pause(guildId, user).statusCode.value() },
            "resume"           to { controller.resume(guildId, user).statusCode.value() },
            "skip"             to { controller.skip(guildId, user, MusicWebController.SkipRequest(1)).statusCode.value() },
            "stop"             to { controller.stop(guildId, user).statusCode.value() },
            "volume"           to { controller.volume(guildId, user, MusicWebController.VolumeRequest(50)).statusCode.value() },
            "seek"             to { controller.seek(guildId, user, MusicWebController.SeekRequest(0)).statusCode.value() },
            "loop"             to { controller.loop(guildId, user, MusicWebController.LoopRequest(true)).statusCode.value() },
            "queue/reorder"    to { controller.reorder(guildId, user, MusicWebController.ReorderRequest(0, 1)).statusCode.value() },
            "queue/{i} DELETE" to { controller.removeQueueItem(guildId, 0, user).statusCode.value() },
            "playlists POST"   to { controller.createPlaylist(guildId, user, MusicWebController.SavePlaylistRequest("x")).statusCode.value() },
            "playlists/{id}/load" to { controller.loadPlaylist(guildId, 1L, user).statusCode.value() },
            "playlists/{id} DELETE" to { controller.deletePlaylist(guildId, 1L, user).statusCode.value() },
        )
        writes.forEach { (label, run) ->
            assertEquals(403, run(), "Expected $label to 403 when musicPermission revoked")
        }
    }

    @Test
    fun `every read endpoint stays accessible when musicPermission is revoked`() {
        // Members keep visibility — only writes are gated.
        every { musicWebService.canControlMusic(discordId, guildId) } returns false
        every { musicWebService.getState(guildId) } returns
            MusicControlGateway.GuildPlayerState(
                guildId, null, 0L, false, 100, false, emptyList(), null,
            )
        every { musicWebService.search(guildId, any(), any()) } returns emptyList()
        every { musicWebService.listPlaylistsForGuild(guildId) } returns emptyList()
        every { sseService.register(guildId) } returns mockk(relaxed = true)

        val reads: List<Pair<String, () -> Int>> = listOf(
            "state"            to { controller.state(guildId, user).statusCode.value() },
            "events"           to { controller.stream(guildId, user).statusCode.value() },
            "search"           to { controller.search(guildId, "linkin park", user).statusCode.value() },
            "playlists GET"    to { controller.listPlaylists(guildId, user).statusCode.value() },
        )
        reads.forEach { (label, run) ->
            assertEquals(200, run(), "Expected $label to 200 even when musicPermission revoked")
        }
    }

    @Test
    fun `read endpoints still 403 for non-members`() {
        // Sanity check: the permission gate is layered on top of membership;
        // non-members must still be cut off from reads.
        val results = listOf(
            controller.state(guildId, nonMemberUser).statusCode.value(),
            controller.stream(guildId, nonMemberUser).statusCode.value(),
            controller.search(guildId, "x", nonMemberUser).statusCode.value(),
            controller.listPlaylists(guildId, nonMemberUser).statusCode.value(),
        )
        results.forEach { assertEquals(403, it) }
    }

    @Test
    fun `write endpoints still 403 for non-members`() {
        val results = listOf(
            controller.load(guildId, nonMemberUser, MusicWebController.LoadRequest("x")).statusCode.value(),
            controller.pause(guildId, nonMemberUser).statusCode.value(),
            controller.skip(guildId, nonMemberUser, MusicWebController.SkipRequest(1)).statusCode.value(),
            controller.volume(guildId, nonMemberUser, MusicWebController.VolumeRequest(50)).statusCode.value(),
            controller.createPlaylist(guildId, nonMemberUser, MusicWebController.SavePlaylistRequest("x")).statusCode.value(),
        )
        results.forEach { assertEquals(403, it) }
    }

    // ---- Mutation validation ------------------------------------------

    @Test
    fun `load with blank query returns 400`() {
        val response = controller.load(guildId, user, MusicWebController.LoadRequest(""))
        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `load with query delegates to service`() {
        every { musicWebService.load(guildId, "linkin park", discordId) } returns LoadResult(true, 1, null)
        val response = controller.load(guildId, user, MusicWebController.LoadRequest("linkin park"))
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        verify(exactly = 1) { musicWebService.load(guildId, "linkin park", discordId) }
    }

    @Test
    fun `load with not-in-voice-channel returns 200 with failure body`() {
        every { musicWebService.load(any(), any(), any()) } returns
            LoadResult(false, 0, "Bot must be in a voice channel")
        val response = controller.load(guildId, user, MusicWebController.LoadRequest("foo"))
        assertEquals(200, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("Bot must be in a voice channel", response.body!!.message)
    }

    @Test
    fun `skip with count of 0 returns 400`() {
        val response = controller.skip(guildId, user, MusicWebController.SkipRequest(0))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `skip with negative count returns 400`() {
        val response = controller.skip(guildId, user, MusicWebController.SkipRequest(-1))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `skip with positive count delegates to service`() {
        every { musicWebService.skip(guildId, 2) } returns true
        val response = controller.skip(guildId, user, MusicWebController.SkipRequest(2))
        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { musicWebService.skip(guildId, 2) }
    }

    @Test
    fun `volume out of range below returns 400`() {
        val response = controller.volume(guildId, user, MusicWebController.VolumeRequest(-1))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `volume out of range above returns 400`() {
        val response = controller.volume(guildId, user, MusicWebController.VolumeRequest(151))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `volume in range succeeds`() {
        every { musicWebService.setVolume(guildId, 75) } returns true
        val response = controller.volume(guildId, user, MusicWebController.VolumeRequest(75))
        assertEquals(200, response.statusCode.value())
    }

    @Test
    fun `seek negative returns 400`() {
        val response = controller.seek(guildId, user, MusicWebController.SeekRequest(-1))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `seek positive delegates to service`() {
        every { musicWebService.seek(guildId, 12_345L) } returns true
        val response = controller.seek(guildId, user, MusicWebController.SeekRequest(12_345L))
        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { musicWebService.seek(guildId, 12_345L) }
    }

    @Test
    fun `reorder negative indices return 400`() {
        val response = controller.reorder(guildId, user, MusicWebController.ReorderRequest(-1, 0))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `reorder invalid indices return 400 when service rejects`() {
        every { musicWebService.reorderQueue(guildId, 0, 99) } returns false
        val response = controller.reorder(guildId, user, MusicWebController.ReorderRequest(0, 99))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `reorder valid indices succeed`() {
        every { musicWebService.reorderQueue(guildId, 1, 3) } returns true
        val response = controller.reorder(guildId, user, MusicWebController.ReorderRequest(1, 3))
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }

    @Test
    fun `removeQueueItem out of bounds returns 404`() {
        every { musicWebService.removeFromQueue(guildId, 50) } returns null
        val response = controller.removeQueueItem(guildId, 50, user)
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `removeQueueItem success returns 200`() {
        every { musicWebService.removeFromQueue(guildId, 2) } returns dummyTrack()
        val response = controller.removeQueueItem(guildId, 2, user)
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }

    // ---- Pause/resume idempotence -------------------------------------

    @Test
    fun `pause when already paused returns ok=false (service reports no-op)`() {
        every { musicWebService.pause(guildId) } returns false
        val response = controller.pause(guildId, user)
        assertEquals(200, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `resume when not paused returns ok=false (service reports no-op)`() {
        every { musicWebService.resume(guildId) } returns false
        val response = controller.resume(guildId, user)
        assertEquals(200, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    // ---- Stop ---------------------------------------------------------

    @Test
    fun `stop delegates to service`() {
        every { musicWebService.stop(guildId) } returns true
        val response = controller.stop(guildId, user)
        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { musicWebService.stop(guildId) }
    }

    // ---- State / events -----------------------------------------------

    @Test
    fun `state returns 404 when no state available`() {
        every { musicWebService.getState(guildId) } returns null
        val response = controller.state(guildId, user)
        assertEquals(404, response.statusCode.value())
        assertNull(response.body!!.state)
    }

    @Test
    fun `state returns 200 with body when available`() {
        val state = MusicControlGateway.GuildPlayerState(
            guildId = guildId,
            nowPlaying = dummyTrack(),
            positionMs = 1000L,
            paused = false,
            volume = 100,
            looping = false,
            queue = emptyList(),
            voiceChannelId = null,
        )
        every { musicWebService.getState(guildId) } returns state
        val response = controller.state(guildId, user)
        assertEquals(200, response.statusCode.value())
        assertNotNull(response.body!!.state)
    }

    @Test
    fun `state unauthenticated returns 401`() {
        val response = controller.state(guildId, null)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `state non-member returns 403`() {
        val response = controller.state(guildId, nonMemberUser)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `events stream unauthenticated returns 401`() {
        val response = controller.stream(guildId, null)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `events stream non-member returns 403`() {
        val response = controller.stream(guildId, nonMemberUser)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `events stream member returns 200 and registers emitter`() {
        every { sseService.register(guildId) } returns mockk(relaxed = true)
        val response = controller.stream(guildId, user)
        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { sseService.register(guildId) }
    }

    // ---- Playlists ----------------------------------------------------

    @Test
    fun `create playlist with blank name returns 400`() {
        val response = controller.createPlaylist(
            guildId, user, MusicWebController.SavePlaylistRequest("")
        )
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `create playlist with duplicate name returns 409`() {
        every { musicWebService.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix") } throws
            PlaylistNameTakenException("taken")
        val response = controller.createPlaylist(
            guildId, user, MusicWebController.SavePlaylistRequest("Mix")
        )
        assertEquals(409, response.statusCode.value())
    }

    @Test
    fun `create playlist empty queue returns 400`() {
        every { musicWebService.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix") } throws
            IllegalArgumentException("Nothing to save")
        val response = controller.createPlaylist(
            guildId, user, MusicWebController.SavePlaylistRequest("Mix")
        )
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `create playlist success returns id`() {
        every { musicWebService.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix") } returns 42L
        val response = controller.createPlaylist(
            guildId, user, MusicWebController.SavePlaylistRequest("Mix")
        )
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(42L, response.body!!.id)
    }

    @Test
    fun `delete playlist as non-owner returns 403`() {
        every { musicWebService.deletePlaylist(7L, discordId, isGuildAdmin = false) } returns false
        val response = controller.deletePlaylist(guildId, 7L, user)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `delete playlist as owner returns 200`() {
        every { musicWebService.deletePlaylist(7L, discordId, isGuildAdmin = false) } returns true
        val response = controller.deletePlaylist(guildId, 7L, user)
        assertEquals(200, response.statusCode.value())
    }

    // ---- Helpers ------------------------------------------------------

    private fun dummyTrack() = TrackInfo(
        identifier = "abc",
        title = "Test",
        author = "Author",
        durationMs = 60_000L,
        uri = "https://example.com",
        artworkUrl = null,
        sourceName = "youtube",
        isStream = false,
        requesterDiscordId = discordId,
    )
}
