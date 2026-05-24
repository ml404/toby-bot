package web.util

import common.leveling.XpAmounts
import database.service.leveling.XpAwardService
import io.mockk.MockKVerificationScope
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.servlet.HandlerMapping

class WebGameplayXpInterceptorTest {

    private val xpAwardService: XpAwardService = mockk(relaxed = true)
    private val interceptor = WebGameplayXpInterceptor(xpAwardService)

    private val discordId = 4242L
    private val guildId = 999L

    @BeforeEach
    fun setup() {
        clearMocks(xpAwardService)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun teardown() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticate(discordIdAttribute: String?) {
        val principal: OAuth2User = mockk(relaxed = true) {
            every { getAttribute<String>("id") } returns discordIdAttribute
        }
        val auth = TestingAuthenticationToken(principal, null)
        SecurityContextHolder.getContext().authentication = auth
    }

    private fun request(
        method: String,
        uri: String,
        guildIdVar: String? = guildId.toString(),
    ): MockHttpServletRequest = MockHttpServletRequest(method, uri).apply {
        requestURI = uri
        if (guildIdVar != null) {
            setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                mapOf("guildId" to guildIdVar)
            )
        }
    }

    private fun runAfterCompletion(
        req: MockHttpServletRequest,
        responseStatus: Int = 200,
    ) {
        val resp = MockHttpServletResponse().apply { status = responseStatus }
        interceptor.afterCompletion(req, resp, Any(), null)
    }

    // The `at: Instant = Instant.now()` default in XpAwardService.award is
    // evaluated at call site, so we use any() for `at` and the daily-cap
    // flag we don't pass explicitly.
    private fun MockKVerificationScope.awardedWithReason(reason: String) =
        xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = XpAmounts.COMMAND_XP,
            reason = reason,
            channelId = null,
            countsAgainstDailyCap = any(),
            at = any(),
        )

    private fun verifyNoAward() {
        verify(exactly = 0) {
            xpAwardService.award(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ---- happy paths across every gameplay/economy family ----

    @Test
    fun `casino slots POST 200 awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"))
        verify(exactly = 1) { awardedWithReason("web:/casino/$guildId/slots/spin") }
    }

    @Test
    fun `blackjack solo deal POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/blackjack/$guildId/solo/deal"))
        verify(exactly = 1) { awardedWithReason("web:/blackjack/$guildId/solo/deal") }
    }

    @Test
    fun `poker table action POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/poker/$guildId/77/action"))
        verify(exactly = 1) { awardedWithReason("web:/poker/$guildId/77/action") }
    }

    @Test
    fun `duel challenge POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/pvp/$guildId/duel/challenge"))
        verify(exactly = 1) { awardedWithReason("web:/pvp/$guildId/duel/challenge") }
    }

    @Test
    fun `economy buy POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/economy/$guildId/buy"))
        verify(exactly = 1) { awardedWithReason("web:/economy/$guildId/buy") }
    }

    @Test
    fun `tip POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/tip/$guildId"))
        verify(exactly = 1) { awardedWithReason("web:/tip/$guildId") }
    }

    @Test
    fun `lottery buy POST awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/casino/$guildId/lottery/match/buy"))
        verify(exactly = 1) { awardedWithReason("web:/casino/$guildId/lottery/match/buy") }
    }

    // ---- guards ----

    @Test
    fun `GET request never awards XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("GET", "/casino/$guildId/slots"))
        verifyNoAward()
    }

    @Test
    fun `4xx response does not award XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"), responseStatus = 400)
        verifyNoAward()
    }

    @Test
    fun `5xx response does not award XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"), responseStatus = 500)
        verifyNoAward()
    }

    @Test
    fun `missing guildId path variable does not award XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/lottery/guilds", guildIdVar = null))
        verifyNoAward()
    }

    @Test
    fun `unparseable guildId does not award XP`() {
        authenticate(discordId.toString())
        runAfterCompletion(request("POST", "/casino/foo/slots/spin", guildIdVar = "foo"))
        verifyNoAward()
    }

    @Test
    fun `anonymous principal does not award XP`() {
        // No SecurityContextHolder authentication set.
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"))
        verifyNoAward()
    }

    @Test
    fun `OAuth2User missing id attribute does not award XP`() {
        authenticate(discordIdAttribute = null)
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"))
        verifyNoAward()
    }

    @Test
    fun `OAuth2User with non-numeric id does not award XP`() {
        authenticate(discordIdAttribute = "not-a-long")
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"))
        verifyNoAward()
    }

    @Test
    fun `non-OAuth principal does not award XP`() {
        // A non-OAuth2 authentication (e.g. anonymous token) should be ignored.
        // Uses TestingAuthenticationToken (purpose-built for test stubs) rather
        // than UsernamePasswordAuthenticationToken — the latter's class name
        // trips GitGuardian's generic-password detector when paired with a
        // literal principal, even though the credentials slot is null.
        val auth = TestingAuthenticationToken("not-an-oauth-user", null)
        SecurityContextHolder.getContext().authentication = auth
        runAfterCompletion(request("POST", "/casino/$guildId/slots/spin"))
        verifyNoAward()
    }
}
