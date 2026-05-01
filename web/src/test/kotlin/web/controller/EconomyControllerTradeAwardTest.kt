package web.controller

import database.service.EconomyTradeService.TradeOutcome
import database.service.SocialCreditAwardService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.EconomyWebService

/**
 * Every successful web-UI buy/sell should funnel one credit through the
 * central award service; every failure outcome must NOT call it. Regression
 * guard for the UI-participation award hook.
 */
class EconomyControllerTradeAwardTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var economyWebService: EconomyWebService
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var user: OAuth2User
    private lateinit var controller: EconomyController

    @BeforeEach
    fun setup() {
        economyWebService = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        // Match the full signature including defaulted args via any().
        every {
            awardService.award(discordId, guildId, EconomyController.UI_TRADE_CREDIT, "ui-trade", any(), any())
        } returns EconomyController.UI_TRADE_CREDIT
        controller = EconomyController(economyWebService, awardService)
    }

    @Test
    fun `successful buy fires the UI trade award`() {
        val outcome = TradeOutcome.Ok(
            amount = 5L,
            transactedCredits = 500L,
            newCoins = 5L,
            newCredits = 500L,
            newPrice = 100.0
        )
        every { economyWebService.buy(discordId, guildId, 5L) } returns outcome

        val response = controller.buy(guildId, TradeRequest(amount = 5L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(true, response.body?.ok)
        assertEquals(500L + EconomyController.UI_TRADE_CREDIT, response.body?.newCredits)
        verify(exactly = 1) {
            awardService.award(discordId, guildId, EconomyController.UI_TRADE_CREDIT, "ui-trade", any(), any())
        }
    }

    @Test
    fun `successful sell fires the UI trade award`() {
        val outcome = TradeOutcome.Ok(
            amount = 3L,
            transactedCredits = 300L,
            newCoins = 2L,
            newCredits = 1300L,
            newPrice = 95.0
        )
        every { economyWebService.sell(discordId, guildId, 3L) } returns outcome

        controller.sell(guildId, TradeRequest(amount = 3L), user)

        verify(exactly = 1) {
            awardService.award(discordId, guildId, EconomyController.UI_TRADE_CREDIT, "ui-trade", any(), any())
        }
    }

    @Test
    fun `insufficient credits outcome does not fire the award`() {
        every { economyWebService.buy(discordId, guildId, 5L) } returns
            TradeOutcome.InsufficientCredits(needed = 500L, have = 10L)

        controller.buy(guildId, TradeRequest(amount = 5L), user)

        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `insufficient coins outcome does not fire the award`() {
        every { economyWebService.sell(discordId, guildId, 5L) } returns
            TradeOutcome.InsufficientCoins(needed = 5L, have = 0L)

        controller.sell(guildId, TradeRequest(amount = 5L), user)

        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown user outcome does not fire the award`() {
        every { economyWebService.buy(discordId, guildId, 5L) } returns TradeOutcome.UnknownUser

        controller.buy(guildId, TradeRequest(amount = 5L), user)

        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `non-positive amount returns 400 without calling the web service or award`() {
        val response = controller.buy(guildId, TradeRequest(amount = 0L), user)

        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { economyWebService.buy(any(), any(), any()) }
        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `clamped award (0 granted) does not inflate the response credits`() {
        val outcome = TradeOutcome.Ok(
            amount = 1L,
            transactedCredits = 100L,
            newCoins = 1L,
            newCredits = 900L,
            newPrice = 100.0
        )
        every { economyWebService.buy(discordId, guildId, 1L) } returns outcome
        every {
            awardService.award(discordId, guildId, EconomyController.UI_TRADE_CREDIT, "ui-trade", any(), any())
        } returns 0L // daily cap reached

        val response = controller.buy(guildId, TradeRequest(amount = 1L), user)

        assertEquals(900L, response.body?.newCredits, "no award added when award returns 0")
    }
}
