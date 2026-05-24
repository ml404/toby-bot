package web.controller.moderation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.ModerationWebService

/**
 * Welcome-tab slice of [ModerationMutationsController] — POST
 * `/auto-role` and DELETE `/auto-role/{roleId}`. Welcome / goodbye
 * scalar settings ride the existing `/config` endpoint and are
 * covered by the `ModerationWebServiceWelcomeTest` validation tests,
 * so no additional controller test is needed for them.
 */
class ModerationMutationsControllerWelcomeTest {

    private val guildId = 42L
    private val actorId = 100L
    private val roleId = 7L

    private lateinit var moderationWebService: ModerationWebService
    private lateinit var user: OAuth2User
    private lateinit var controller: ModerationMutationsController

    @BeforeEach
    fun setup() {
        moderationWebService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns actorId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        controller = ModerationMutationsController(moderationWebService)
    }

    // ---- addAutoRole ----

    @Test
    fun `addAutoRole returns 400 on non-numeric role id`() {
        val response = controller.addAutoRole(guildId, AutoRoleRequest(roleId = "abc"), user)
        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("Invalid role id.", response.body!!.error)
        verify(exactly = 0) { moderationWebService.addAutoRole(any(), any(), any()) }
    }

    @Test
    fun `addAutoRole returns 401 when user is not signed in`() {
        every { user.getAttribute<String>("id") } returns null
        val response = controller.addAutoRole(guildId, AutoRoleRequest(roleId = roleId.toString()), user)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { moderationWebService.addAutoRole(any(), any(), any()) }
    }

    @Test
    fun `addAutoRole dispatches to service and returns ok on success`() {
        every { moderationWebService.addAutoRole(actorId, guildId, roleId) } returns null
        val response = controller.addAutoRole(guildId, AutoRoleRequest(roleId = roleId.toString()), user)
        assertTrue(response.statusCode.is2xxSuccessful)
        assertTrue(response.body!!.ok)
        assertNull(response.body!!.error)
    }

    @Test
    fun `addAutoRole surfaces service error in 400`() {
        every { moderationWebService.addAutoRole(actorId, guildId, roleId) } returns "Cannot auto-assign @everyone."
        val response = controller.addAutoRole(guildId, AutoRoleRequest(roleId = roleId.toString()), user)
        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("Cannot auto-assign @everyone.", response.body!!.error)
    }

    // ---- removeAutoRole ----

    @Test
    fun `removeAutoRole returns 401 when user is not signed in`() {
        every { user.getAttribute<String>("id") } returns null
        val response = controller.removeAutoRole(guildId, roleId, user)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { moderationWebService.removeAutoRole(any(), any(), any()) }
    }

    @Test
    fun `removeAutoRole dispatches to service on success`() {
        every { moderationWebService.removeAutoRole(actorId, guildId, roleId) } returns null
        val response = controller.removeAutoRole(guildId, roleId, user)
        assertTrue(response.statusCode.is2xxSuccessful)
        assertTrue(response.body!!.ok)
    }

    @Test
    fun `removeAutoRole surfaces service error in 400`() {
        every { moderationWebService.removeAutoRole(actorId, guildId, roleId) } returns "Only the server owner can change guild config."
        val response = controller.removeAutoRole(guildId, roleId, user)
        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("Only the server owner can change guild config.", response.body!!.error)
    }
}
