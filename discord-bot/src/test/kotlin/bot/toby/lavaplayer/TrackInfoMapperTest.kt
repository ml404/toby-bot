package bot.toby.lavaplayer

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
}
