package web.util

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import web.service.EconomyWebService

class WebGuildAccessTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var economyWebService: EconomyWebService
    private lateinit var ra: RedirectAttributes

    @BeforeEach
    fun setup() {
        economyWebService = mockk(relaxed = true)
        ra = mockk(relaxed = true)
    }

    private fun mockUser(idAttribute: String?): OAuth2User = mockk(relaxed = true) {
        every { getAttribute<String>("id") } returns idAttribute
    }

    // ---- requireMemberForPage ----

    @Test
    fun `page redirects anonymous to lobby with no flash`() {
        val anon: OAuth2User? = null

        val result = WebGuildAccess.requireMemberForPage(
            anon, guildId, economyWebService, ra, lobbyPath = "/casino/guilds"
        ) { _ -> error("block must not run") }

        assertEquals("redirect:/casino/guilds", result)
        verify(exactly = 0) { economyWebService.isMember(any(), any()) }
        verify(exactly = 0) { ra.addFlashAttribute(any(), any()) }
    }

    @Test
    fun `page redirects user with no discord id attribute`() {
        // OAuth2User exists but doesn't expose an "id" attribute (rare but
        // possible if the OAuth provider sends back an unexpected payload).
        val user = mockUser(idAttribute = null)

        val result = WebGuildAccess.requireMemberForPage(
            user, guildId, economyWebService, ra, lobbyPath = "/poker/guilds"
        ) { _ -> error("block must not run") }

        assertEquals("redirect:/poker/guilds", result)
        verify(exactly = 0) { economyWebService.isMember(any(), any()) }
    }

    @Test
    fun `page redirects non-member with the not-a-member flash`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns false

        val result = WebGuildAccess.requireMemberForPage(
            user, guildId, economyWebService, ra, lobbyPath = "/duel/guilds"
        ) { _ -> error("block must not run") }

        assertEquals("redirect:/duel/guilds", result)
        verify(exactly = 1) { ra.addFlashAttribute("error", "You are not a member of that server.") }
    }

    @Test
    fun `page invokes block with discord id when member`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns true

        val result = WebGuildAccess.requireMemberForPage(
            user, guildId, economyWebService, ra, lobbyPath = "/duel/guilds"
        ) { id ->
            assertEquals(discordId, id)
            "duel"
        }

        assertEquals("duel", result)
        verify(exactly = 0) { ra.addFlashAttribute(any(), any()) }
    }

    @Test
    fun `page propagates a redirect returned from the block`() {
        // The block can choose to redirect for its own reasons (e.g. table
        // missing). The helper just returns whatever the block returns.
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns true

        val result = WebGuildAccess.requireMemberForPage(
            user, guildId, economyWebService, ra, lobbyPath = "/poker/guilds"
        ) { _ -> "redirect:/poker/$guildId" }

        assertEquals("redirect:/poker/$guildId", result)
    }

    // ---- requireMemberForJson ----

    @Test
    fun `json builds a 401 envelope for anonymous request`() {
        val anon: OAuth2User? = null
        val errorBuilder: (Int) -> ResponseEntity<String> = { status ->
            ResponseEntity.status(status).body("err-$status")
        }

        val result = WebGuildAccess.requireMemberForJson(
            anon, guildId, economyWebService, errorBuilder
        ) { _ -> error("block must not run") }

        assertEquals(401, result.statusCode.value())
        assertEquals("err-401", result.body)
    }

    @Test
    fun `json builds a 403 envelope for non-member`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns false

        val result = WebGuildAccess.requireMemberForJson<String>(
            user, guildId, economyWebService,
            errorBuilder = { status -> ResponseEntity.status(status).body("err-$status") }
        ) { _ -> error("block must not run") }

        assertEquals(403, result.statusCode.value())
        assertEquals("err-403", result.body)
    }

    @Test
    fun `json invokes block with discord id when member`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns true

        val result = WebGuildAccess.requireMemberForJson<String>(
            user, guildId, economyWebService,
            errorBuilder = { _ -> error("errorBuilder must not run") }
        ) { id ->
            assertEquals(discordId, id)
            ResponseEntity.ok("ok")
        }

        assertEquals(200, result.statusCode.value())
        assertEquals("ok", result.body)
    }

    @Test
    fun `json block can short-circuit with a non-success ResponseEntity`() {
        // Blocks routinely return badRequest() for input-validation
        // failures *after* the auth/member check has passed. The helper
        // must not interfere with that.
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns true

        val result = WebGuildAccess.requireMemberForJson<String>(
            user, guildId, economyWebService,
            errorBuilder = { _ -> error("errorBuilder must not run") }
        ) { _ ->
            ResponseEntity.badRequest().body("validation failed")
        }

        assertEquals(400, result.statusCode.value())
        assertEquals("validation failed", result.body)
    }

    // ---- requireMemberForJsonNoBody ----

    @Test
    fun `noBody returns bare 401 for anonymous`() {
        val result = WebGuildAccess.requireMemberForJsonNoBody<String>(
            null, guildId, economyWebService
        ) { _ -> error("block must not run") }

        assertEquals(401, result.statusCode.value())
        assertNull(result.body)
    }

    @Test
    fun `noBody returns bare 403 for non-member`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns false

        val result = WebGuildAccess.requireMemberForJsonNoBody<String>(
            user, guildId, economyWebService
        ) { _ -> error("block must not run") }

        assertEquals(403, result.statusCode.value())
        assertNull(result.body)
    }

    @Test
    fun `noBody invokes block with discord id when member`() {
        val user = mockUser(idAttribute = discordId.toString())
        every { economyWebService.isMember(discordId, guildId) } returns true

        val result = WebGuildAccess.requireMemberForJsonNoBody<String>(
            user, guildId, economyWebService
        ) { id ->
            assertEquals(discordId, id)
            ResponseEntity.ok("payload")
        }

        assertEquals(200, result.statusCode.value())
        assertEquals("payload", result.body)
    }
}
