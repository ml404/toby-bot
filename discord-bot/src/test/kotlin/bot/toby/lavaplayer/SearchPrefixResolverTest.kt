package bot.toby.lavaplayer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchPrefixResolverTest {

    @Test
    fun `https URL is returned unchanged`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            SearchPrefixResolver.resolve("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun `http URL is returned unchanged`() {
        assertEquals(
            "http://example.com/song.mp3",
            SearchPrefixResolver.resolve("http://example.com/song.mp3"),
        )
    }

    @Test
    fun `URL with query params and fragments is returned unchanged`() {
        val url = "https://open.spotify.com/track/abc?si=xyz#fragment"
        assertEquals(url, SearchPrefixResolver.resolve(url))
    }

    @Test
    fun `Spotify URL passes through`() {
        val url = "https://open.spotify.com/track/4iV5W9uYEdYUVa79Axb7Rh"
        assertEquals(url, SearchPrefixResolver.resolve(url))
    }

    @Test
    fun `SoundCloud URL passes through`() {
        val url = "https://soundcloud.com/artist/track-name"
        assertEquals(url, SearchPrefixResolver.resolve(url))
    }

    @Test
    fun `Bandcamp URL passes through`() {
        val url = "https://artist.bandcamp.com/track/song"
        assertEquals(url, SearchPrefixResolver.resolve(url))
    }

    @Test
    fun `Vimeo URL passes through`() {
        val url = "https://vimeo.com/123456789"
        assertEquals(url, SearchPrefixResolver.resolve(url))
    }

    @Test
    fun `ytsearch prefix passes through`() {
        assertEquals("ytsearch:linkin park", SearchPrefixResolver.resolve("ytsearch:linkin park"))
    }

    @Test
    fun `ytmsearch prefix passes through`() {
        assertEquals("ytmsearch:metric", SearchPrefixResolver.resolve("ytmsearch:metric"))
    }

    @Test
    fun `scsearch prefix passes through`() {
        assertEquals("scsearch:lofi beats", SearchPrefixResolver.resolve("scsearch:lofi beats"))
    }

    @Test
    fun `spsearch prefix passes through`() {
        assertEquals("spsearch:doves", SearchPrefixResolver.resolve("spsearch:doves"))
    }

    @Test
    fun `sprec prefix passes through`() {
        assertEquals("sprec:abc123", SearchPrefixResolver.resolve("sprec:abc123"))
    }

    @Test
    fun `dzsearch prefix passes through`() {
        assertEquals("dzsearch:foo", SearchPrefixResolver.resolve("dzsearch:foo"))
    }

    @Test
    fun `dzisrc prefix passes through`() {
        assertEquals("dzisrc:USRC17607839", SearchPrefixResolver.resolve("dzisrc:USRC17607839"))
    }

    @Test
    fun `amsearch prefix passes through`() {
        assertEquals("amsearch:beyonce", SearchPrefixResolver.resolve("amsearch:beyonce"))
    }

    @Test
    fun `ymsearch prefix passes through`() {
        assertEquals("ymsearch:rachmaninoff", SearchPrefixResolver.resolve("ymsearch:rachmaninoff"))
    }

    @Test
    fun `ymrec prefix passes through`() {
        assertEquals("ymrec:12345", SearchPrefixResolver.resolve("ymrec:12345"))
    }

    @Test
    fun `prefix matching is case insensitive`() {
        assertEquals("YTSearch:foo", SearchPrefixResolver.resolve("YTSearch:foo"))
        assertEquals("SCSEARCH:foo", SearchPrefixResolver.resolve("SCSEARCH:foo"))
        assertEquals("SpSearch:foo", SearchPrefixResolver.resolve("SpSearch:foo"))
    }

    @Test
    fun `single-word plain query is coerced to ytsearch`() {
        assertEquals("ytsearch:linkin", SearchPrefixResolver.resolve("linkin"))
    }

    @Test
    fun `multi-word plain query is coerced to ytsearch`() {
        assertEquals(
            "ytsearch:linkin park in the end",
            SearchPrefixResolver.resolve("linkin park in the end"),
        )
    }

    @Test
    fun `plain query with punctuation is coerced`() {
        assertEquals(
            "ytsearch:Metric - Help I'm Alive!",
            SearchPrefixResolver.resolve("Metric - Help I'm Alive!"),
        )
    }

    @Test
    fun `empty input returns empty without prefix`() {
        assertEquals("", SearchPrefixResolver.resolve(""))
    }

    @Test
    fun `whitespace-only input returns empty without prefix`() {
        assertEquals("", SearchPrefixResolver.resolve("   "))
        assertEquals("", SearchPrefixResolver.resolve("\t\n  "))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before coercion`() {
        assertEquals("ytsearch:hello world", SearchPrefixResolver.resolve("  hello world  "))
    }

    @Test
    fun `query containing prefix word mid-string is still coerced`() {
        assertEquals(
            "ytsearch:play ytsearch:foo",
            SearchPrefixResolver.resolve("play ytsearch:foo"),
        )
    }

    @Test
    fun `custom default prefix is honored`() {
        assertEquals(
            "scsearch:lofi beats",
            SearchPrefixResolver.resolve("lofi beats", defaultPrefix = "scsearch:"),
        )
    }

    @Test
    fun `custom default prefix is not applied to URLs`() {
        val url = "https://www.youtube.com/watch?v=abc"
        assertEquals(url, SearchPrefixResolver.resolve(url, defaultPrefix = "scsearch:"))
    }

    @Test
    fun `custom default prefix is not applied to explicit prefixed input`() {
        assertEquals(
            "ytsearch:foo",
            SearchPrefixResolver.resolve("ytsearch:foo", defaultPrefix = "scsearch:"),
        )
    }
}
