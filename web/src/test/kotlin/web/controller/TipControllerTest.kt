package web.controller

import database.service.TipService
import database.service.TipService.TipOutcome
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.oauth2.core.user.OAuth2User
import web.event.WebTipSentEvent
import web.service.EconomyWebService
import web.service.TipWebService

class TipControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val recipientId = 200L

    private lateinit var tipService: TipService
    private lateinit var tipWebService: TipWebService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var user: OAuth2User
    private lateinit var controller: TipController

    @BeforeEach
    fun setup() {
        tipService = mockk(relaxed = true)
        tipWebService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        every { economyWebService.isMember(recipientId, guildId) } returns true
        controller = TipController(
            tipService, tipWebService, economyWebService, userService, jda, eventPublisher
        )
    }

    @Test
    fun `tip Ok returns 200 with balances and daily totals and publishes WebTipSentEvent`() {
        every {
            tipService.tip(discordId, recipientId, guildId, 50L, "thanks", any(), any())
        } returns TipOutcome.Ok(
            sender = discordId, recipient = recipientId,
            amount = 50L, note = "thanks",
            senderNewBalance = 950L, recipientNewBalance = 1050L,
            sentTodayAfter = 50L, dailyCap = 500L
        )

        val response = controller.tip(guildId, TipRequest(recipientId, 50L, "thanks"), user)

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertTrue(body.ok)
        assertEquals(50L, body.amount)
        assertEquals(950L, body.senderNewBalance)
        assertEquals(1050L, body.recipientNewBalance)
        assertEquals(50L, body.sentTodayAfter)
        assertEquals(500L, body.dailyCap)
        verify { tipWebService.ensureRecipient(recipientId, guildId) }
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                WebTipSentEvent(
                    guildId = guildId,
                    senderDiscordId = discordId,
                    recipientDiscordId = recipientId,
                    amount = 50L,
                    note = "thanks",
                    senderNewBalance = 950L,
                    recipientNewBalance = 1050L,
                    sentTodayAfter = 50L,
                    dailyCap = 500L
                )
            )
        }
    }

    @Test
    fun `tip without auth returns 401`() {
        val anon = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns null
        }

        val response = controller.tip(guildId, TipRequest(recipientId, 50L, null), anon)

        assertEquals(401, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `tip when not a member returns 403`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.tip(guildId, TipRequest(recipientId, 50L, null), user)

        assertEquals(403, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `tip to non-member returns 400 without calling service`() {
        every { economyWebService.isMember(recipientId, guildId) } returns false

        val response = controller.tip(guildId, TipRequest(recipientId, 50L, null), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { tipWebService.ensureRecipient(any(), any()) }
        verify(exactly = 0) { tipService.tip(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tip to self returns 400 before service is called`() {
        val response = controller.tip(guildId, TipRequest(discordId, 50L, null), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { tipService.tip(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `each non-Ok TipOutcome maps to a 400 with error`() {
        val outcomes = listOf(
            TipOutcome.InvalidAmount(10L, 500L),
            TipOutcome.InvalidRecipient(TipOutcome.InvalidRecipient.Reason.BOT),
            TipOutcome.InsufficientCredits(have = 30L, needed = 50L),
            TipOutcome.DailyCapExceeded(sentToday = 480L, cap = 500L, attempted = 50L),
            TipOutcome.UnknownSender,
            TipOutcome.UnknownRecipient,
        )
        outcomes.forEach { variant ->
            every { tipService.tip(discordId, recipientId, guildId, 50L, null, any(), any()) } returns variant
            val response = controller.tip(guildId, TipRequest(recipientId, 50L, null), user)
            assertEquals(400, response.statusCode.value(), "outcome $variant should be 400")
            assertFalse(response.body!!.ok)
            assertNull(response.body!!.amount)
        }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebTipSentEvent>()) }
    }
}
