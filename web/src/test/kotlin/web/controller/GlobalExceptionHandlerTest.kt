package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Pins the open-redirect fix on the max-upload-size handler: the Referer
 * header is attacker-controllable, so only its in-app path (+ query) may
 * feed the redirect — absolute external targets are reduced to their path,
 * and protocol-relative / opaque / unparseable values fall back to the
 * intro picker.
 */
internal class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    private fun handle(referer: String?): String {
        val request = mockk<HttpServletRequest> {
            every { getHeader("Referer") } returns referer
        }
        val ra = mockk<RedirectAttributes>(relaxed = true)
        return handler.handleMaxUploadSize(request, ra)
    }

    @Test
    fun `redirects back to the referring in-app path`() {
        assertEquals("redirect:/intro/manage", handle("https://bot.example/intro/manage"))
    }

    @Test
    fun `keeps the referring page's query string`() {
        assertEquals(
            "redirect:/intro/manage?guildId=42",
            handle("https://bot.example/intro/manage?guildId=42")
        )
    }

    @Test
    fun `falls back when the referer is missing`() {
        assertEquals("redirect:/intro/guilds", handle(null))
    }

    @Test
    fun `does not follow an external host with no path`() {
        assertEquals("redirect:/intro/guilds", handle("https://evil.example"))
    }

    @Test
    fun `does not follow a protocol-relative referer`() {
        assertEquals("redirect:/intro/guilds", handle("//evil.example"))
    }

    @Test
    fun `does not follow an opaque scheme referer`() {
        assertEquals("redirect:/intro/guilds", handle("javascript:alert(1)"))
    }

    @Test
    fun `does not follow an unparseable referer`() {
        assertEquals("redirect:/intro/guilds", handle("ht!tp://%%%"))
    }

    @Test
    fun `flashes the size error message`() {
        val request = mockk<HttpServletRequest> { every { getHeader("Referer") } returns null }
        val ra = mockk<RedirectAttributes>(relaxed = true)
        handler.handleMaxUploadSize(request, ra)
        verify { ra.addFlashAttribute("error", "File too large. Maximum size is 550KB.") }
    }
}
