package bot.toby.helpers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class URLHelperTest {

    @Test
    fun `isValidURL returns true for valid http URL`() {
        assertTrue(URLHelper.isValidURL("http://example.com"))
    }

    @Test
    fun `isValidURL returns true for valid https URL`() {
        assertTrue(URLHelper.isValidURL("https://www.example.com/path?query=value"))
    }

    @Test
    fun `isValidURL returns false for null input`() {
        assertFalse(URLHelper.isValidURL(null))
    }

    @Test
    fun `isValidURL returns false for empty string`() {
        assertFalse(URLHelper.isValidURL(""))
    }

    @Test
    fun `isValidURL returns false for malformed URL`() {
        assertFalse(URLHelper.isValidURL("not a url"))
    }

    @Test
    fun `isValidURL returns false for URL with spaces`() {
        assertFalse(URLHelper.isValidURL("http://example .com"))
    }

    @Test
    fun `fromUrlString returns URI for valid URL`() {
        val uri = URLHelper.fromUrlString("https://example.com/path")
        assertNotNull(uri)
        assertEquals("https", uri!!.scheme)
        assertEquals("example.com", uri.host)
    }

    @Test
    fun `fromUrlString returns null for null input`() {
        assertNull(URLHelper.fromUrlString(null))
    }

    @Test
    fun `fromUrlString returns null for invalid URL string`() {
        assertNull(URLHelper.fromUrlString("not a url"))
    }

    @Test
    fun `fromUrlString returns null for empty string`() {
        assertNull(URLHelper.fromUrlString(""))
    }
}
