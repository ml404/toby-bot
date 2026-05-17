package bot.toby.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class SourceBadgesTest {

    @Test
    fun `youtube maps to red`() {
        val badge = SourceBadges.forSource("youtube")
        assertEquals("YouTube", badge.displayName)
        assertEquals(Color(0xFF0000), badge.color)
    }

    @Test
    fun `spotify maps to spotify green`() {
        val badge = SourceBadges.forSource("spotify")
        assertEquals("Spotify", badge.displayName)
        assertEquals(Color(0x1DB954), badge.color)
    }

    @Test
    fun `soundcloud maps to orange`() {
        val badge = SourceBadges.forSource("soundcloud")
        assertEquals("SoundCloud", badge.displayName)
        assertEquals(Color(0xFF5500), badge.color)
    }

    @Test
    fun `apple music maps to applemusic key`() {
        val badge = SourceBadges.forSource("applemusic")
        assertEquals("Apple Music", badge.displayName)
        assertEquals(Color(0xFA243C), badge.color)
    }

    @Test
    fun `bandcamp maps correctly`() {
        val badge = SourceBadges.forSource("bandcamp")
        assertEquals("Bandcamp", badge.displayName)
    }

    @Test
    fun `vimeo maps correctly`() {
        val badge = SourceBadges.forSource("vimeo")
        assertEquals("Vimeo", badge.displayName)
    }

    @Test
    fun `deezer maps correctly`() {
        val badge = SourceBadges.forSource("deezer")
        assertEquals("Deezer", badge.displayName)
    }

    @Test
    fun `yandexmusic maps correctly`() {
        val badge = SourceBadges.forSource("yandexmusic")
        assertEquals("Yandex Music", badge.displayName)
    }

    @Test
    fun `twitch maps to twitch purple`() {
        val badge = SourceBadges.forSource("twitch")
        assertEquals("Twitch", badge.displayName)
        assertEquals(Color(0x9146FF), badge.color)
    }

    @Test
    fun `case-insensitive lookup works`() {
        val lower = SourceBadges.forSource("youtube")
        val upper = SourceBadges.forSource("YOUTUBE")
        val mixed = SourceBadges.forSource("YouTube")
        assertEquals(lower.displayName, upper.displayName)
        assertEquals(lower.displayName, mixed.displayName)
        assertEquals(lower.color, upper.color)
    }

    @Test
    fun `unknown source falls back to default`() {
        val badge = SourceBadges.forSource("definitely-not-a-real-source")
        assertEquals("Music", badge.displayName)
        // Default color is not any of the known source colors
        assertNotEquals(Color(0xFF0000), badge.color)
        assertNotEquals(Color(0x1DB954), badge.color)
    }

    @Test
    fun `null source falls back to default`() {
        val badge = SourceBadges.forSource(null)
        assertEquals("Music", badge.displayName)
    }
}
