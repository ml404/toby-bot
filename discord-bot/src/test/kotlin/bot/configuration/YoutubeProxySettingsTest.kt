package bot.configuration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class YoutubeProxySettingsTest {

    private fun envOf(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `returns null when host is missing`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(YoutubeProxySettings.PORT_ENV to "8080")
        )
        assertNull(result)
    }

    @Test
    fun `returns null when host is blank`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(
                YoutubeProxySettings.HOST_ENV to "  ",
                YoutubeProxySettings.PORT_ENV to "8080",
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns null when port is missing`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(YoutubeProxySettings.HOST_ENV to "proxy.example.com")
        )
        assertNull(result)
    }

    @Test
    fun `returns null when port is not numeric`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(
                YoutubeProxySettings.HOST_ENV to "proxy.example.com",
                YoutubeProxySettings.PORT_ENV to "not-a-port",
            )
        )
        assertNull(result)
    }

    @Test
    fun `parses host and port without credentials`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(
                YoutubeProxySettings.HOST_ENV to "proxy.example.com",
                YoutubeProxySettings.PORT_ENV to "8080",
            )
        )!!
        assertEquals("proxy.example.com", result.host)
        assertEquals(8080, result.port)
        assertNull(result.user)
        assertNull(result.pass)
        assertFalse(result.hasAuth)
    }

    @Test
    fun `parses host port and credentials`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(
                YoutubeProxySettings.HOST_ENV to "proxy.example.com",
                YoutubeProxySettings.PORT_ENV to "8080",
                YoutubeProxySettings.USER_ENV to "alice",
                YoutubeProxySettings.PASS_ENV to "s3cret",
            )
        )!!
        assertEquals("alice", result.user)
        assertEquals("s3cret", result.pass)
        assertTrue(result.hasAuth)
    }

    @Test
    fun `treats blank credentials as absent`() {
        val result = YoutubeProxySettings.fromEnv(
            envOf(
                YoutubeProxySettings.HOST_ENV to "proxy.example.com",
                YoutubeProxySettings.PORT_ENV to "8080",
                YoutubeProxySettings.USER_ENV to "  ",
                YoutubeProxySettings.PASS_ENV to "",
            )
        )!!
        assertNull(result.user)
        assertNull(result.pass)
        assertFalse(result.hasAuth)
    }
}
