package web.controller

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
import web.service.PurgeResult

class ModerationControllerTest {

    private val guildId = 42L
    private val actorId = 100L
    private val targetId = 200L

    private lateinit var moderationWebService: ModerationWebService
    private lateinit var user: OAuth2User
    private lateinit var controller: ModerationController

    @BeforeEach
    fun setup() {
        moderationWebService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns actorId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        controller = ModerationController(moderationWebService, mockk(relaxed = true), "test-client-id")
    }

    // ---- ban ----

    @Test
    fun `ban returns 400 when targetDiscordId is not numeric`() {
        val response = controller.ban(guildId, BanRequest(targetDiscordId = "abc"), user)
        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("Invalid user id.", response.body!!.error)
        verify(exactly = 0) { moderationWebService.banMember(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ban dispatches to service and returns ok on success`() {
        every {
            moderationWebService.banMember(actorId, guildId, targetId, "spam", 1)
        } returns null

        val response = controller.ban(
            guildId, BanRequest(targetDiscordId = targetId.toString(), reason = "spam", deleteDays = 1), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        assertTrue(response.body!!.ok)
        assertNull(response.body!!.error)
    }

    @Test
    fun `ban surfaces service error in 400`() {
        every {
            moderationWebService.banMember(actorId, guildId, targetId, null, 0)
        } returns "You can't ban Alice."

        val response = controller.ban(
            guildId, BanRequest(targetDiscordId = targetId.toString()), user
        )

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertEquals("You can't ban Alice.", response.body!!.error)
    }

    @Test
    fun `ban returns 401 when user is not signed in`() {
        every { user.getAttribute<String>("id") } returns null
        val response = controller.ban(
            guildId, BanRequest(targetDiscordId = targetId.toString()), user
        )
        assertEquals(401, response.statusCode.value())
    }

    // ---- unban ----

    @Test
    fun `unban returns 400 when id is not numeric`() {
        val response = controller.unban(guildId, UnbanRequest(targetDiscordId = "xyz"), user)
        assertEquals(400, response.statusCode.value())
        assertEquals("Invalid user id.", response.body!!.error)
    }

    @Test
    fun `unban dispatches to service`() {
        every { moderationWebService.unbanUser(actorId, guildId, targetId) } returns null
        val response = controller.unban(
            guildId, UnbanRequest(targetDiscordId = targetId.toString()), user
        )
        assertTrue(response.body!!.ok)
    }

    // ---- timeout ----

    @Test
    fun `timeout returns 400 when targetDiscordId is not numeric`() {
        val response = controller.timeout(
            guildId, TimeoutRequest(targetDiscordId = "abc", minutes = 10), user
        )
        assertEquals(400, response.statusCode.value())
        assertEquals("Invalid user id.", response.body!!.error)
    }

    @Test
    fun `timeout dispatches duration and reason to service`() {
        every {
            moderationWebService.timeoutMember(actorId, guildId, targetId, 15L, "loud")
        } returns null

        val response = controller.timeout(
            guildId,
            TimeoutRequest(targetDiscordId = targetId.toString(), minutes = 15L, reason = "loud"),
            user
        )

        assertTrue(response.body!!.ok)
        verify(exactly = 1) {
            moderationWebService.timeoutMember(actorId, guildId, targetId, 15L, "loud")
        }
    }

    // ---- untimeout ----

    @Test
    fun `untimeout dispatches to service`() {
        every { moderationWebService.untimeoutMember(actorId, guildId, targetId) } returns null
        val response = controller.untimeout(
            guildId, UntimeoutRequest(targetDiscordId = targetId.toString()), user
        )
        assertTrue(response.body!!.ok)
    }

    // ---- purge ----

    @Test
    fun `purge returns 400 when channel id is not numeric`() {
        val response = controller.purge(
            guildId, PurgeRequest(channelId = "abc", count = 10), user
        )
        assertEquals(400, response.statusCode.value())
        assertEquals("Invalid channel id.", response.body!!.error)
    }

    @Test
    fun `purge dispatches and surfaces deleted plus skipped counts`() {
        every {
            moderationWebService.purgeMessages(actorId, guildId, 999L, 10, null)
        } returns PurgeResult(deleted = 7, skipped = 3)

        val response = controller.purge(
            guildId, PurgeRequest(channelId = "999", count = 10), user
        )

        assertTrue(response.body!!.ok)
        assertEquals(7, response.body!!.deleted)
        assertEquals(3, response.body!!.skipped)
    }

    @Test
    fun `purge surfaces service error`() {
        every {
            moderationWebService.purgeMessages(actorId, guildId, 999L, 200, null)
        } returns PurgeResult(error = "Count must be between 1 and 100.")

        val response = controller.purge(
            guildId, PurgeRequest(channelId = "999", count = 200), user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Count must be between 1 and 100.", response.body!!.error)
    }

    @Test
    fun `purge parses filterUserId when supplied`() {
        every {
            moderationWebService.purgeMessages(actorId, guildId, 999L, 10, targetId)
        } returns PurgeResult(deleted = 2)

        val response = controller.purge(
            guildId,
            PurgeRequest(channelId = "999", count = 10, filterUserId = targetId.toString()),
            user
        )

        assertTrue(response.body!!.ok)
        verify(exactly = 1) {
            moderationWebService.purgeMessages(actorId, guildId, 999L, 10, targetId)
        }
    }

    // ---- lock / slowmode ----

    @Test
    fun `lock dispatches with lock=true`() {
        every { moderationWebService.lockChannel(actorId, guildId, 999L, true) } returns null
        val response = controller.lockChannel(guildId, 999L, LockRequest(lock = true), user)
        assertTrue(response.body!!.ok)
        verify(exactly = 1) { moderationWebService.lockChannel(actorId, guildId, 999L, true) }
    }

    @Test
    fun `lock surfaces service error`() {
        every { moderationWebService.lockChannel(actorId, guildId, 999L, false) } returns "boom"
        val response = controller.lockChannel(guildId, 999L, LockRequest(lock = false), user)
        assertEquals(400, response.statusCode.value())
        assertEquals("boom", response.body!!.error)
    }

    @Test
    fun `slowmode dispatches seconds`() {
        every { moderationWebService.setSlowmode(actorId, guildId, 999L, 30) } returns null
        val response = controller.slowmode(guildId, 999L, SlowmodeRequest(seconds = 30), user)
        assertTrue(response.body!!.ok)
        verify(exactly = 1) { moderationWebService.setSlowmode(actorId, guildId, 999L, 30) }
    }
}
