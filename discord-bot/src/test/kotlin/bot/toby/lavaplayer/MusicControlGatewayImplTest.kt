package bot.toby.lavaplayer

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.managers.AudioManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue

class MusicControlGatewayImplTest {

    private val guildId = 100L

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var audioManager: AudioManager
    private lateinit var playerManager: PlayerManager
    private lateinit var musicManager: GuildMusicManager
    private lateinit var trackScheduler: TrackScheduler
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var gateway: MusicControlGatewayImpl

    @BeforeEach
    fun setUp() {
        jda = mockk()
        guild = mockk()
        audioManager = mockk(relaxed = true)
        playerManager = mockk(relaxed = true)
        musicManager = mockk()
        trackScheduler = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.audioManager } returns audioManager
        every { musicManager.audioPlayer } returns audioPlayer
        every { musicManager.scheduler } returns trackScheduler
        every { trackScheduler.queue } returns LinkedBlockingQueue()
        every { trackScheduler.isLooping } returns false

        mockkObject(PlayerManager.Companion)
        every { PlayerManager.instance } returns playerManager
        every { playerManager.getMusicManager(guild) } returns musicManager

        gateway = MusicControlGatewayImpl(jdaSupplier = JdaSupplier { jda })
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(PlayerManager.Companion)
    }

    @Test
    fun `getState returns null when guild not found`() {
        every { jda.getGuildById(999L) } returns null
        assertNull(gateway.getState(999L))
    }

    @Test
    fun `getState returns snapshot with no playing track`() {
        every { audioPlayer.playingTrack } returns null
        every { audioPlayer.isPaused } returns false
        every { audioPlayer.volume } returns 100
        every { audioManager.connectedChannel } returns null

        val state = gateway.getState(guildId)
        assertNotNull(state)
        assertNull(state!!.nowPlaying)
        assertEquals(0L, state.positionMs)
        assertEquals(100, state.volume)
    }

    @Test
    fun `getState returns track info with requester when playing`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 60_000L, "id", false, "https://example.com")
        every { track.position } returns 5_000L
        every { track.duration } returns 60_000L
        every { track.sourceManager } returns null
        every { audioPlayer.playingTrack } returns track
        every { audioPlayer.isPaused } returns true
        every { audioPlayer.volume } returns 80
        every { trackScheduler.getRequesterId(track) } returns 42L
        every { audioManager.connectedChannel } returns null

        val state = gateway.getState(guildId)
        assertNotNull(state)
        assertEquals(true, state!!.paused)
        assertEquals(80, state.volume)
        val nowPlaying = state.nowPlaying
        assertNotNull(nowPlaying)
        assertEquals("T", nowPlaying!!.title)
        assertEquals(42L, nowPlaying.requesterDiscordId)
        assertEquals(5_000L, state.positionMs)
    }

    @Test
    fun `getState surfaces the connected voice channel and its members`() {
        // Bot already joined a VC; the dashboard surfaces who's listening.
        val voiceChannel = mockk<net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion>()
        val alice = mockk<net.dv8tion.jda.api.entities.Member>()
        val bot = mockk<net.dv8tion.jda.api.entities.Member>()
        val aliceUser = mockk<net.dv8tion.jda.api.entities.User>()
        val botUser = mockk<net.dv8tion.jda.api.entities.User>()
        val aliceVoice = mockk<net.dv8tion.jda.api.entities.GuildVoiceState>(relaxed = true)
        val botVoice = mockk<net.dv8tion.jda.api.entities.GuildVoiceState>(relaxed = true)

        every { audioManager.connectedChannel } returns voiceChannel
        every { voiceChannel.idLong } returns 555L
        every { voiceChannel.name } returns "🎶 music"
        every { voiceChannel.members } returns listOf(alice, bot)
        every { alice.idLong } returns 1L
        every { alice.effectiveName } returns "Alice"
        every { alice.effectiveAvatarUrl } returns "https://cdn/alice.png"
        every { alice.user } returns aliceUser
        every { aliceUser.isBot } returns false
        every { alice.voiceState } returns aliceVoice
        every { aliceVoice.isMuted } returns false
        every { aliceVoice.isSelfMuted } returns false
        every { aliceVoice.isDeafened } returns false
        every { aliceVoice.isSelfDeafened } returns true
        every { bot.idLong } returns 999L
        every { bot.effectiveName } returns "TobyBot"
        every { bot.effectiveAvatarUrl } returns "https://cdn/toby.png"
        every { bot.user } returns botUser
        every { botUser.isBot } returns true
        every { bot.voiceState } returns botVoice
        every { botVoice.isMuted } returns false
        every { botVoice.isSelfMuted } returns false
        every { botVoice.isDeafened } returns false
        every { botVoice.isSelfDeafened } returns false
        every { audioPlayer.playingTrack } returns null
        every { audioPlayer.isPaused } returns false
        every { audioPlayer.volume } returns 100

        val state = gateway.getState(guildId)
        assertNotNull(state)
        val voice = state!!.voiceChannel
        assertNotNull(voice)
        assertEquals(555L, voice!!.id)
        assertEquals("🎶 music", voice.name)
        assertEquals(2, voice.members.size)

        val aliceInfo = voice.members.first { it.discordId == 1L }
        assertEquals("Alice", aliceInfo.displayName)
        assertEquals(false, aliceInfo.isBot)
        assertEquals(true, aliceInfo.isDeafened) // self-deafened still counts
        assertEquals(false, aliceInfo.isMuted)

        val botInfo = voice.members.first { it.discordId == 999L }
        assertEquals(true, botInfo.isBot)
    }

    @Test
    fun `getState voice channel is null when bot not connected`() {
        every { audioManager.connectedChannel } returns null
        every { audioPlayer.playingTrack } returns null
        every { audioPlayer.isPaused } returns false
        every { audioPlayer.volume } returns 100
        assertNull(gateway.getState(guildId)?.voiceChannel)
    }

    @Test
    fun `load returns failure when bot is not in voice channel`() {
        every { audioManager.connectedChannel } returns null
        val result = gateway.load(guildId, "linkin park", requesterDiscordId = 1L)
        assertFalse(result.ok)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("voice channel"))
    }

    @Test
    fun `pause toggles when not already paused`() {
        every { audioPlayer.isPaused } returns false
        assertTrue(gateway.pause(guildId))
        verify(exactly = 1) { audioPlayer.isPaused = true }
    }

    @Test
    fun `pause is no-op when already paused`() {
        every { audioPlayer.isPaused } returns true
        assertFalse(gateway.pause(guildId))
        verify(exactly = 0) { audioPlayer.isPaused = true }
    }

    @Test
    fun `resume toggles when paused`() {
        every { audioPlayer.isPaused } returns true
        assertTrue(gateway.resume(guildId))
        verify(exactly = 1) { audioPlayer.isPaused = false }
    }

    @Test
    fun `resume is no-op when not paused`() {
        every { audioPlayer.isPaused } returns false
        assertFalse(gateway.resume(guildId))
    }

    @Test
    fun `skip count of 0 returns false`() {
        assertFalse(gateway.skip(guildId, 0))
        verify(exactly = 0) { trackScheduler.nextTrack() }
    }

    @Test
    fun `skip negative count returns false`() {
        assertFalse(gateway.skip(guildId, -3))
        verify(exactly = 0) { trackScheduler.nextTrack() }
    }

    @Test
    fun `skip 3 calls nextTrack 3 times`() {
        gateway.skip(guildId, 3)
        verify(exactly = 3) { trackScheduler.nextTrack() }
    }

    @Test
    fun `setVolume clamps below zero to zero`() {
        gateway.setVolume(guildId, -10)
        verify(exactly = 1) { audioPlayer.volume = 0 }
    }

    @Test
    fun `setVolume clamps above 150 to 150`() {
        gateway.setVolume(guildId, 999)
        verify(exactly = 1) { audioPlayer.volume = 150 }
    }

    @Test
    fun `setVolume in range passes through unclamped`() {
        gateway.setVolume(guildId, 75)
        verify(exactly = 1) { audioPlayer.volume = 75 }
    }

    @Test
    fun `seek negative returns false`() {
        assertFalse(gateway.seek(guildId, -1L))
    }

    @Test
    fun `seek with no playing track returns false`() {
        every { audioPlayer.playingTrack } returns null
        assertFalse(gateway.seek(guildId, 1000L))
    }

    @Test
    fun `seek beyond duration returns false`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.duration } returns 10_000L
        every { audioPlayer.playingTrack } returns track
        assertFalse(gateway.seek(guildId, 20_000L))
    }

    @Test
    fun `seek within duration updates track position`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.duration } returns 60_000L
        every { audioPlayer.playingTrack } returns track
        assertTrue(gateway.seek(guildId, 5_000L))
        verify(exactly = 1) { track.position = 5_000L }
    }

    @Test
    fun `reorderQueue delegates to scheduler`() {
        every { trackScheduler.moveQueueItem(0, 2) } returns true
        assertTrue(gateway.reorderQueue(guildId, 0, 2))
        verify(exactly = 1) { trackScheduler.moveQueueItem(0, 2) }
    }

    @Test
    fun `removeFromQueue returns null when scheduler returns null`() {
        every { trackScheduler.removeQueueItem(50) } returns null
        assertNull(gateway.removeFromQueue(guildId, 50))
    }

    @Test
    fun `removeFromQueue returns TrackInfo when scheduler returns a track`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("Removed", "Author", 30_000L, "id", false, null)
        every { track.duration } returns 30_000L
        every { track.sourceManager } returns null
        every { trackScheduler.removeQueueItem(2) } returns track

        val result = gateway.removeFromQueue(guildId, 2)
        assertNotNull(result)
        assertEquals("Removed", result!!.title)
    }

    @Test
    fun `setLooping idempotent when state unchanged`() {
        every { trackScheduler.isLooping } returns true
        assertTrue(gateway.setLooping(guildId, true))
        verify(exactly = 0) { trackScheduler.isLooping = any() }
    }

    @Test
    fun `setLooping flips state`() {
        every { trackScheduler.isLooping } returns false
        assertTrue(gateway.setLooping(guildId, true))
        verify(exactly = 1) { trackScheduler.isLooping = true }
    }

    @Test
    fun `search returns empty list when no jda supplier`() {
        val noJda = MusicControlGatewayImpl(jdaSupplier = JdaSupplier { null })
        assertTrue(noJda.search(guildId, "linkin park").isEmpty())
    }

    @Test
    fun `search returns empty list when guild is missing`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(gateway.search(guildId, "linkin park").isEmpty())
    }

    @Test
    fun `search forwards a single track from trackLoaded`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 1L, "id", false, "https://example.com")
        every { track.duration } returns 1L
        every { track.sourceManager } returns null

        // Wire the lavaplayer load path to fire trackLoaded synchronously.
        every { playerManager.loadForExternal(any(), any(), any()) } answers {
            val handler = thirdArg<com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler>()
            handler.trackLoaded(track)
        }

        val results = gateway.search(guildId, "https://example.com")
        assertEquals(1, results.size)
        assertEquals("T", results[0].title)
    }

    @Test
    fun `search caps the result list at the requested limit`() {
        val playlist = mockk<com.sedmelluq.discord.lavaplayer.track.AudioPlaylist>(relaxed = true)
        val tracks = (1..20).map {
            mockk<AudioTrack>(relaxed = true).also { t ->
                every { t.info } returns AudioTrackInfo("Track $it", "A", 1L, "id-$it", false, null)
                every { t.duration } returns 1L
                every { t.sourceManager } returns null
            }
        }
        every { playlist.tracks } returns tracks
        every { playerManager.loadForExternal(any(), any(), any()) } answers {
            val handler = thirdArg<com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler>()
            handler.playlistLoaded(playlist)
        }

        val results = gateway.search(guildId, "linkin park", limit = 5)
        assertEquals(5, results.size)
    }

    @Test
    fun `search returns empty list on noMatches`() {
        every { playerManager.loadForExternal(any(), any(), any()) } answers {
            val handler = thirdArg<com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler>()
            handler.noMatches()
        }
        assertTrue(gateway.search(guildId, "asdfqwer").isEmpty())
    }

    @Test
    fun `gateway with null JDA supplier returns null state and false mutators`() {
        val noJda = MusicControlGatewayImpl(jdaSupplier = JdaSupplier { null })
        assertNull(noJda.getState(guildId))
        assertFalse(noJda.pause(guildId))
        assertFalse(noJda.resume(guildId))
        assertFalse(noJda.skip(guildId))
        assertFalse(noJda.setVolume(guildId, 50))
    }
}
