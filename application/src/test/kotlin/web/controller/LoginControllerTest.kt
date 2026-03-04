package web.controller

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ui.Model

class LoginControllerTest {

    private lateinit var controller: LoginController
    private lateinit var model: Model

    @BeforeEach
    fun setup() {
        controller = LoginController()
        model = mockk(relaxed = true)
    }

    @Test
    fun `login without error returns login view`() {
        val view = controller.login(null, model)

        assertEquals("login", view)
        verify(exactly = 0) { model.addAttribute(any<String>(), any()) }
    }

    @Test
    fun `login with error adds loginError attribute`() {
        val view = controller.login("true", model)

        assertEquals("login", view)
        verify { model.addAttribute("loginError", true) }
    }
}
