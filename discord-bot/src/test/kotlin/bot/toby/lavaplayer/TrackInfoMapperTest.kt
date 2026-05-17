package bot.toby.lavaplayer

import com.github.topi314.lavasrc.ExtendedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrackInfoMapperTest {

    @Test
    fun `maps title author duration uri identifier`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("Title", "Author", 60_000L, "id-123", false, "https://example.com")
        every { track.duration } returns 60_000L
        every { track.sourceManager } returns null

        val info = TrackInfoMapper.toTrackInfo(track, requesterId = 42L)
        assertEquals("Title", info.title)
        assertEquals("Author", info.author)
        assertEquals("id-123", info.identifier)
        assertEquals(60_000L, info.durationMs)
        assertEquals("https://example.com", info.uri)
        assertEquals(42L, info.requesterDiscordId)
        assertEquals(false, info.isStream)
    }

    @Test
    fun `maps requesterId null when not provided`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 0L, "id", true, null)
        every { track.duration } returns 0L
        every { track.sourceManager } returns null

        val info = TrackInfoMapper.toTrackInfo(track, requesterId = null)
        assertNull(info.requesterDiscordId)
        assertEquals(true, info.isStream)
    }

    @Test
    fun `swallows exceptions from sourceManager access`() {
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 1L, "id", false, "u")
        every { track.duration } returns 1L
        every { track.sourceManager } throws RuntimeException("boom")

        val info = TrackInfoMapper.toTrackInfo(track, null)
        assertNull(info.sourceName)
    }

    @Test
    fun `previewUrl is null for non-LavaSrc tracks`() {
        // A vanilla AudioTrack (no ExtendedAudioTrack interface, like YouTube
        // / SoundCloud / Bandcamp loads) won't pass the cast — the dashboard
        // hides the Preview button entirely for these.
        val track = mockk<AudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 1L, "id", false, "u")
        every { track.duration } returns 1L
        every { track.sourceManager } returns null

        val info = TrackInfoMapper.toTrackInfo(track, null)
        assertNull(info.previewUrl)
    }

    @Test
    fun `previewUrl is populated for ExtendedAudioTrack instances`() {
        // LavaSrc's Spotify / Apple / Deezer / Yandex tracks all implement
        // ExtendedAudioTrack and expose getPreviewUrl(). We hand the cast
        // result through unchanged.
        val track = mockk<ExtendedAudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("Spotify Track", "Artist", 200_000L, "spotify:123", false, "https://open.spotify.com/track/123")
        every { track.duration } returns 200_000L
        every { track.sourceManager } returns null
        every { track.previewUrl } returns "https://p.scdn.co/mp3-preview/abc"

        val info = TrackInfoMapper.toTrackInfo(track, requesterId = 7L)
        assertEquals("https://p.scdn.co/mp3-preview/abc", info.previewUrl)
    }

    @Test
    fun `previewUrl is null when ExtendedAudioTrack getter returns null`() {
        // Edge: the track is an ExtendedAudioTrack but the source didn't
        // publish a preview for this particular result (region-restricted,
        // podcast, etc.). Same outcome as a non-LavaSrc track — no button.
        val track = mockk<ExtendedAudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 1L, "id", false, "u")
        every { track.duration } returns 1L
        every { track.sourceManager } returns null
        every { track.previewUrl } returns null

        val info = TrackInfoMapper.toTrackInfo(track, null)
        assertNull(info.previewUrl)
    }

    @Test
    fun `swallows exceptions from ExtendedAudioTrack previewUrl getter`() {
        // Defensive: if a future LavaSrc release throws inside getPreviewUrl
        // (e.g. lazy network resolution), we don't want to bring down every
        // track-info mapping. Fall through to null.
        val track = mockk<ExtendedAudioTrack>(relaxed = true)
        every { track.info } returns AudioTrackInfo("T", "A", 1L, "id", false, "u")
        every { track.duration } returns 1L
        every { track.sourceManager } returns null
        every { track.previewUrl } throws RuntimeException("boom")

        val info = TrackInfoMapper.toTrackInfo(track, null)
        assertNull(info.previewUrl)
    }
}
