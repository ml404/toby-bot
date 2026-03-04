package web.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.support.RedirectAttributes

class GlobalExceptionHandlerTest {

    private lateinit var handler: GlobalExceptionHandler
    private lateinit var request: HttpServletRequest
    private lateinit var ra: RedirectAttributes

    @BeforeEach
    fun setup() {
        handler = GlobalExceptionHandler()
        request = mockk(relaxed = true)
        ra = mockk(relaxed = true)
    }

    @Test
    fun `handleMaxUploadSize redirects to referer with error message`() {
        every { request.getHeader("Referer") } returns "/intro/123"

        val result = handler.handleMaxUploadSize(request, ra)

        assertEquals("redirect:/intro/123", result)
        verify { ra.addFlashAttribute("error", "File too large. Maximum size is 550KB.") }
    }

    @Test
    fun `handleMaxUploadSize falls back to guilds page when referer is null`() {
        every { request.getHeader("Referer") } returns null

        val result = handler.handleMaxUploadSize(request, ra)

        assertEquals("redirect:/intro/guilds", result)
        verify { ra.addFlashAttribute("error", "File too large. Maximum size is 550KB.") }
    }
}
