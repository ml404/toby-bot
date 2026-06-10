package web.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.ui.ConcurrentModel
import web.service.HomeStatsService

class HomeControllerTest {

    private val homeStatsService = mockk<HomeStatsService> {
        every { get() } returns mockk()
    }
    private val controller = HomeController("12345", homeStatsService)

    @Test
    fun `a discord activity launch on the root is forwarded to the shell with its params intact`() {
        // Discord always loads the proxy root "/" with the SDK params in
        // the query string; the Embedded App SDK needs every one of them
        // (frame_id, instance_id, platform, ...) present on the shell URL.
        val request = MockHttpServletRequest("GET", "/")
        request.queryString = "instance_id=i-1&frame_id=abc&platform=mobile"

        val view = controller.home(
            user = null,
            frameId = "abc",
            request = request,
            model = ConcurrentModel(),
        )

        assertEquals("redirect:/activity?instance_id=i-1&frame_id=abc&platform=mobile", view)
    }

    @Test
    fun `a normal browser visit renders the homepage`() {
        val model = ConcurrentModel()

        val view = controller.home(
            user = null,
            frameId = null,
            request = MockHttpServletRequest("GET", "/"),
            model = model,
        )

        assertEquals("home", view)
    }
}
