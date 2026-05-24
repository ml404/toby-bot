package web.service

import core.music.MusicControlGateway
import core.music.MusicControlGateway.GuildPlayerState
import core.music.MusicControlGateway.LoadResult
import core.music.MusicControlGateway.TrackInfo
import database.dto.music.MusicPlaylistDto
import database.dto.music.MusicPlaylistItemDto
import database.service.music.MusicPlaylistService
import database.service.music.MusicPlaylistService.PlaylistItemInput
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership

class MusicWebServiceTest {

    private val guildId = 100L
    private val discordId = 200L

    private lateinit var gateway: MusicControlGateway
    private lateinit var playlistService: MusicPlaylistService
    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var membership: GuildMembership
    private lateinit var userService: database.service.user.UserService
    private lateinit var service: MusicWebService

    @BeforeEach
    fun setUp() {
        gateway = mockk(relaxed = true)
        playlistService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        membership = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = MusicWebService(gateway, playlistService, jda, introWebService, membership, userService)
    }

    @Test
    fun `load trims query and passes to gateway`() {
        every { gateway.load(guildId, "linkin park", discordId) } returns LoadResult(true, 1, null)
        val result = service.load(guildId, "   linkin park  ", discordId)
        assertTrue(result.ok)
        verify(exactly = 1) { gateway.load(guildId, "linkin park", discordId) }
    }

    @Test
    fun `saveCurrentQueueAsPlaylist captures nowPlaying plus queue and uses uri when present`() {
        val nowPlaying = TrackInfo("id1", "Title 1", "Author 1", 100L, "https://yt/1", null, "youtube", false, null)
        val queued1 = TrackInfo("id2", "Title 2", "Author 2", 200L, null, null, "spotify", false, null)
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, nowPlaying, 0L, false, 100, false, listOf(queued1), null)

        val itemsSlot = slot<List<PlaylistItemInput>>()
        val saved = MusicPlaylistDto(id = 42L, guildId = guildId, ownerDiscordId = discordId, name = "Mix")
        every {
            playlistService.create(guildId, discordId, "Mix", capture(itemsSlot))
        } returns saved

        val id = service.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix")

        assertEquals(42L, id)
        val items = itemsSlot.captured
        assertEquals(2, items.size)
        assertEquals("https://yt/1", items[0].identifier)
        // No URI on queued1 -> falls back to the lavaplayer identifier
        assertEquals("id2", items[1].identifier)
    }

    @Test
    fun `saveCurrentQueueAsPlaylist throws when state unavailable`() {
        every { gateway.getState(guildId) } returns null
        assertThrows(IllegalStateException::class.java) {
            service.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix")
        }
    }

    @Test
    fun `saveCurrentQueueAsPlaylist throws when queue is empty`() {
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, null, 0L, false, 100, false, emptyList(), null)
        assertThrows(IllegalArgumentException::class.java) {
            service.saveCurrentQueueAsPlaylist(guildId, discordId, "Mix")
        }
    }

    @Test
    fun `loadPlaylistIntoQueue returns not found for unknown id`() {
        every { playlistService.getById(99L) } returns null
        val result = service.loadPlaylistIntoQueue(guildId, 99L, discordId)
        assertFalse(result.ok)
        assertEquals("Playlist not found", result.message)
    }

    @Test
    fun `loadPlaylistIntoQueue rejects cross-guild access`() {
        val playlist = MusicPlaylistDto(id = 1L, guildId = 999L, ownerDiscordId = discordId, name = "x")
        every { playlistService.getById(1L) } returns playlist
        val result = service.loadPlaylistIntoQueue(guildId, 1L, discordId)
        assertFalse(result.ok)
    }

    @Test
    fun `loadPlaylistIntoQueue loads each item via gateway in order`() {
        val item1 = MusicPlaylistItemDto(position = 0, identifier = "a")
        val item2 = MusicPlaylistItemDto(position = 1, identifier = "b")
        val playlist = MusicPlaylistDto(
            id = 1L,
            guildId = guildId,
            ownerDiscordId = discordId,
            name = "Mix",
            items = mutableListOf(item2, item1),
        )
        every { playlistService.getById(1L) } returns playlist
        every { gateway.load(guildId, "a", discordId) } returns LoadResult(true, 1, null)
        every { gateway.load(guildId, "b", discordId) } returns LoadResult(true, 1, null)

        val result = service.loadPlaylistIntoQueue(guildId, 1L, discordId)
        assertTrue(result.ok)
        assertEquals(2, result.tracksLoaded)
        assertEquals(0, result.tracksFailed)
    }

    @Test
    fun `loadPlaylistIntoQueue tracks failed loads`() {
        val items = mutableListOf(
            MusicPlaylistItemDto(position = 0, identifier = "good"),
            MusicPlaylistItemDto(position = 1, identifier = "bad"),
        )
        val playlist = MusicPlaylistDto(
            id = 1L, guildId = guildId, ownerDiscordId = discordId, name = "Mix", items = items,
        )
        every { playlistService.getById(1L) } returns playlist
        every { gateway.load(guildId, "good", discordId) } returns LoadResult(true, 1, null)
        every { gateway.load(guildId, "bad", discordId) } returns LoadResult(false, 0, "nope")
        val result = service.loadPlaylistIntoQueue(guildId, 1L, discordId)
        assertEquals(1, result.tracksLoaded)
        assertEquals(1, result.tracksFailed)
    }

    @Test
    fun `deletePlaylist as owner returns true`() {
        val playlist = MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = discordId, name = "Mix")
        every { playlistService.getById(1L) } returns playlist
        val ok = service.deletePlaylist(1L, discordId, isGuildAdmin = false)
        assertTrue(ok)
        verify(exactly = 1) { playlistService.deleteById(1L) }
    }

    @Test
    fun `deletePlaylist as non-owner non-admin returns false`() {
        val playlist = MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = 9999L, name = "Mix")
        every { playlistService.getById(1L) } returns playlist
        val ok = service.deletePlaylist(1L, discordId, isGuildAdmin = false)
        assertFalse(ok)
        verify(exactly = 0) { playlistService.deleteById(any()) }
    }

    @Test
    fun `deletePlaylist as admin bypasses owner check`() {
        val playlist = MusicPlaylistDto(id = 1L, guildId = guildId, ownerDiscordId = 9999L, name = "Mix")
        every { playlistService.getById(1L) } returns playlist
        val ok = service.deletePlaylist(1L, discordId, isGuildAdmin = true)
        assertTrue(ok)
        verify(exactly = 1) { playlistService.deleteById(1L) }
    }

    @Test
    fun `deletePlaylist returns false when playlist not found`() {
        every { playlistService.getById(1L) } returns null
        val ok = service.deletePlaylist(1L, discordId, isGuildAdmin = false)
        assertFalse(ok)
    }

    @Test
    fun `isMember delegates to GuildMembership`() {
        every { membership.isMember(discordId, guildId) } returns true
        assertTrue(service.isMember(discordId, guildId))
        every { membership.isMember(discordId, guildId) } returns false
        assertFalse(service.isMember(discordId, guildId))
    }

    @Test
    fun `canControlMusic returns false when not a member`() {
        every { membership.isMember(discordId, guildId) } returns false
        assertFalse(service.canControlMusic(discordId, guildId))
    }

    @Test
    fun `canControlMusic returns true when member and user has no row yet`() {
        // First-time user: no UserDto persisted, defaults to permitted.
        every { membership.isMember(discordId, guildId) } returns true
        every { userService.getUserById(discordId, guildId) } returns null
        assertTrue(service.canControlMusic(discordId, guildId))
    }

    @Test
    fun `canControlMusic respects user musicPermission flag`() {
        every { membership.isMember(discordId, guildId) } returns true
        val userRow = mockk<database.dto.user.UserDto>()
        every { userRow.musicPermission } returns true
        every { userService.getUserById(discordId, guildId) } returns userRow
        assertTrue(service.canControlMusic(discordId, guildId))

        every { userRow.musicPermission } returns false
        assertFalse(service.canControlMusic(discordId, guildId))
    }

    @Test
    fun `enrichRequester fills displayName and avatarUrl from the guild`() {
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val member = mockk<net.dv8tion.jda.api.entities.Member>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(42L) } returns member
        every { member.effectiveName } returns "Alice"
        every { member.effectiveAvatarUrl } returns "https://cdn/avatar.png"

        val track = TrackInfo("id", "T", "A", 0L, null, null, "youtube", false, requesterDiscordId = 42L)
        val enriched = service.enrichRequester(track, guildId)
        assertEquals("Alice", enriched.requesterDisplayName)
        assertEquals("https://cdn/avatar.png", enriched.requesterAvatarUrl)
    }

    @Test
    fun `enrichRequester is a no-op when track has no requester id`() {
        val track = TrackInfo("id", "T", "A", 0L, null, null, "youtube", false, requesterDiscordId = null)
        val enriched = service.enrichRequester(track, guildId)
        assertNull(enriched.requesterDisplayName)
        assertNull(enriched.requesterAvatarUrl)
    }

    @Test
    fun `enrichRequester is a no-op when member no longer in the guild`() {
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(42L) } returns null

        val track = TrackInfo("id", "T", "A", 0L, null, null, "youtube", false, requesterDiscordId = 42L)
        val enriched = service.enrichRequester(track, guildId)
        assertNull(enriched.requesterDisplayName)
        assertNull(enriched.requesterAvatarUrl)
    }

    @Test
    fun `getState enriches nowPlaying and queue tracks`() {
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val alice = mockk<net.dv8tion.jda.api.entities.Member>()
        val bob = mockk<net.dv8tion.jda.api.entities.Member>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(1L) } returns alice
        every { guild.getMemberById(2L) } returns bob
        every { alice.effectiveName } returns "Alice"
        every { alice.effectiveAvatarUrl } returns "a"
        every { bob.effectiveName } returns "Bob"
        every { bob.effectiveAvatarUrl } returns "b"

        val nowPlaying = TrackInfo("id1", "Now", "X", 0L, null, null, "yt", false, requesterDiscordId = 1L)
        val queued = TrackInfo("id2", "Next", "Y", 0L, null, null, "yt", false, requesterDiscordId = 2L)
        every { gateway.getState(guildId) } returns
            GuildPlayerState(guildId, nowPlaying, 0L, false, 100, false, listOf(queued), null)

        val state = service.getState(guildId)
        assertNotNull(state)
        assertEquals("Alice", state!!.nowPlaying!!.requesterDisplayName)
        assertEquals("Bob", state.queue[0].requesterDisplayName)
    }
}
