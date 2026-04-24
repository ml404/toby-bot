package web.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.BuyWithTobyOutcome
import web.service.TitlesWebService

/**
 * Maps each [BuyWithTobyOutcome] to the correct HTTP status + body. Guards
 * the one-click buy endpoint against accidentally leaking internal state
 * or returning the wrong status on error paths.
 */
class TitlesControllerBuyWithTobyTest {

    private val guildId = 777L
    private val titleId = 9L
    private val discordId = 101L

    private lateinit var titlesWebService: TitlesWebService
    private lateinit var user: OAuth2User
    private lateinit var controller: TitlesController

    @BeforeEach
    fun setup() {
        titlesWebService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { titlesWebService.isMember(discordId, guildId) } returns true
        controller = TitlesController(titlesWebService)
    }

    @Test
    fun `Ok maps to 200 with populated body`() {
        every { titlesWebService.buyTitleWithTobyCoin(discordId, guildId, titleId) } returns
            BuyWithTobyOutcome.Ok(soldTobyCoins = 40L, newCoins = 60L, newCredits = 5L, newPrice = 2.48)

        val response = controller.buyWithToby(guildId, titleId, user)

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertTrue(body.ok)
        assertEquals(40L, body.soldTobyCoins)
        assertEquals(60L, body.newCoins)
        assertEquals(5L, body.newCredits)
        assertEquals(2.48, body.newPrice)
    }

    @Test
    fun `InsufficientCoins maps to 400 with human-readable error`() {
        every { titlesWebService.buyTitleWithTobyCoin(discordId, guildId, titleId) } returns
            BuyWithTobyOutcome.InsufficientCoins(needed = 200L, have = 5L)

        val response = controller.buyWithToby(guildId, titleId, user)

        assertEquals(400, response.statusCode.value())
        val body = response.body!!
        assertFalse(body.ok)
        val error = body.error!!
        assertTrue(error.contains("200 TOBY"))
        assertTrue(error.contains("5"))
    }

    @Test
    fun `AlreadyOwns maps to 400 with 'already own' message`() {
        every { titlesWebService.buyTitleWithTobyCoin(discordId, guildId, titleId) } returns
            BuyWithTobyOutcome.AlreadyOwns

        val response = controller.buyWithToby(guildId, titleId, user)

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body!!.error!!.contains("already own"))
    }

    @Test
    fun `Error outcome maps to 400 with propagated message`() {
        every { titlesWebService.buyTitleWithTobyCoin(discordId, guildId, titleId) } returns
            BuyWithTobyOutcome.Error("Title not found.")

        val response = controller.buyWithToby(guildId, titleId, user)

        assertEquals(400, response.statusCode.value())
        assertEquals("Title not found.", response.body!!.error)
    }

    @Test
    fun `non-member returns 403 and never invokes the service`() {
        every { titlesWebService.isMember(discordId, guildId) } returns false

        val response = controller.buyWithToby(guildId, titleId, user)

        assertEquals(403, response.statusCode.value())
        io.mockk.verify(exactly = 0) { titlesWebService.buyTitleWithTobyCoin(any(), any(), any()) }
    }

    @Test
    fun `unauthenticated user returns 401`() {
        val anon: OAuth2User = mockk {
            every { getAttribute<String>("id") } returns null
        }

        val response = controller.buyWithToby(guildId, titleId, anon)

        assertEquals(401, response.statusCode.value())
    }
}
