package web.controller.admin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.AdminInstallsService
import web.service.BotOwnerAuthorizer

class AdminControllerTest {

    private val ownerId = 777L
    private val strangerId = 123L

    private lateinit var adminInstallsService: AdminInstallsService
    private lateinit var controller: AdminController
    private val botOwnerAuthorizer = BotOwnerAuthorizer(ownerId.toString())

    @BeforeEach
    fun setup() {
        adminInstallsService = mockk(relaxed = true)
        controller = AdminController(adminInstallsService, botOwnerAuthorizer)
    }

    private fun userWithId(id: Long): OAuth2User = mockk {
        every { getAttribute<String>("id") } returns id.toString()
        every { getAttribute<String>("username") } returns "tester"
    }

    @Test
    fun `operator sees the installs page with the install list in the model`() {
        val rows = listOf(
            AdminInstallsService.InstallRow(
                guildId = "10", guildName = "Alpha", iconUrl = null,
                ownerId = "1", ownerName = "Alice", memberCount = 5,
                installMode = "express", installedAtMillis = 1700000000000L,
            )
        )
        every { adminInstallsService.listInstalls() } returns rows
        val model = mockk<Model>(relaxed = true)

        val view = controller.installs(userWithId(ownerId), model, mockk(relaxed = true))

        assertEquals("admin-installs", view)
        verify { model.addAttribute("installs", rows) }
    }

    @Test
    fun `non-operator is redirected home and never queries the service`() {
        val ra = mockk<RedirectAttributes>(relaxed = true)

        val view = controller.installs(userWithId(strangerId), mockk(relaxed = true), ra)

        assertEquals("redirect:/", view)
        verify(exactly = 0) { adminInstallsService.listInstalls() }
        verify { ra.addFlashAttribute("error", "Not authorized.") }
    }

    @Test
    fun `anonymous user is redirected home`() {
        val view = controller.installs(null, mockk(relaxed = true), mockk(relaxed = true))

        assertEquals("redirect:/", view)
        verify(exactly = 0) { adminInstallsService.listInstalls() }
    }
}
