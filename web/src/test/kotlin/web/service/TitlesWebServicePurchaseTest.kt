package web.service

import database.dto.TitleDto
import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for the one-click "Buy with TOBY" flow. The flow must atomically
 * top up the credit shortfall by selling just enough TobyCoins at the
 * live market price.
 */
class TitlesWebServicePurchaseTest {

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var userService: UserService
    private lateinit var titleService: TitleService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var service: TitlesWebService

    private val guildId = 777L
    private val discordId = 101L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild

        service = TitlesWebService(
            jda = jda,
            userService = userService,
            titleService = titleService,
            titleRoleService = mockk(relaxed = true),
            introWebService = mockk(relaxed = true),
            marketService = marketService,
            tradeService = tradeService
        )
    }

    private fun market(price: Double) = TobyCoinMarketDto(guildId = guildId, price = price, lastTickAt = Instant.now())

    @Test
    fun `pure-TOBY path sells exactly ceil(cost price) coins`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 1_000L  // plenty to cover ceil(500/2.5) = 200
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)
        // sell adjusts the mocked user in-memory so the re-read sees new values
        every { tradeService.sell(discordId, guildId, 200L) } answers {
            actor.socialCredit = 500L
            actor.tobyCoins -= 200L
            TradeOutcome.Ok(
                amount = 200L,
                transactedCredits = 500L,
                newCoins = actor.tobyCoins,
                newCredits = actor.socialCredit ?: 0L,
                newPrice = 2.49
            )
        }

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val ok = assertInstanceOf(BuyWithTobyOutcome.Ok::class.java, outcome)
        assertEquals(200L, ok.soldTobyCoins, "ceil(500/2.5) = 200")
        assertEquals(0L, ok.newCredits, "credits = 500 sold - 500 cost")
        assertEquals(2.49, ok.newPrice, 1e-9)
        verify(exactly = 1) { tradeService.sell(discordId, guildId, 200L) }
        verify(exactly = 1) { titleService.recordPurchase(discordId, 9L) }
    }

    @Test
    fun `top-up path sells just the shortfall`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 200L
            tobyCoins = 100L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarketForUpdate(guildId) } returns market(10.0)
        // shortfall = 500 - 200 = 300, price = 10 → 30 coins needed
        every { tradeService.sell(discordId, guildId, 30L) } answers {
            actor.socialCredit = (actor.socialCredit ?: 0L) + 300L
            actor.tobyCoins -= 30L
            TradeOutcome.Ok(
                amount = 30L,
                transactedCredits = 300L,
                newCoins = actor.tobyCoins,
                newCredits = actor.socialCredit ?: 0L,
                newPrice = 9.7
            )
        }

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val ok = assertInstanceOf(BuyWithTobyOutcome.Ok::class.java, outcome)
        assertEquals(30L, ok.soldTobyCoins)
        assertEquals(0L, ok.newCredits, "200 + 300 - 500 = 0")
        assertEquals(70L, ok.newCoins, "100 TOBY - 30 sold")
        verify(exactly = 1) { tradeService.sell(discordId, guildId, 30L) }
    }

    @Test
    fun `no-TOBY-needed path skips the market entirely`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 100L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 1_000L
            tobyCoins = 10L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarket(guildId) } returns market(5.0)

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val ok = assertInstanceOf(BuyWithTobyOutcome.Ok::class.java, outcome)
        assertEquals(0L, ok.soldTobyCoins)
        assertEquals(900L, ok.newCredits)
        assertEquals(10L, ok.newCoins, "coins untouched")
        verify(exactly = 0) { tradeService.sell(any(), any(), any()) }
        verify(exactly = 0) { marketService.getMarketForUpdate(any()) }
    }

    @Test
    fun `insufficient TOBY reports needed vs have without touching state`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 5L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val insufficient = assertInstanceOf(BuyWithTobyOutcome.InsufficientCoins::class.java, outcome)
        assertEquals(200L, insufficient.needed)
        assertEquals(5L, insufficient.have)
        verify(exactly = 0) { tradeService.sell(any(), any(), any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `already-owned title returns AlreadyOwns with no DB mutation`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 500L
            tobyCoins = 0L
        }
        every { titleService.getById(9L) } returns title
        // User lock is acquired BEFORE the owns check (so concurrent callers
        // serialise). Ownership is then observed inside the lock.
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { titleService.owns(discordId, 9L) } returns true

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        assertEquals(BuyWithTobyOutcome.AlreadyOwns, outcome)
        verify(exactly = 0) { tradeService.sell(any(), any(), any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `title-not-found returns Error without any writes`() {
        every { titleService.getById(404L) } returns null

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 404L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("Title not found"))
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `bot not in server returns Error early`() {
        every { jda.getGuildById(guildId) } returns null

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("Bot is not in that server"))
    }

    @Test
    fun `missing user profile returns Error`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 100L)
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("profile"))
    }

    @Test
    fun `missing market for uncreated guild returns Error`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 1_000L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarketForUpdate(guildId) } returns null

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("market"))
    }

    @Test
    fun `getTitlesForGuild populates tobyCoins and marketPrice`() {
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 123L
            tobyCoins = 47L
        }
        every { titleService.listAll() } returns listOf(TitleDto(id = 1L, label = "X", cost = 10L))
        every { titleService.listOwned(discordId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns actor
        every { marketService.getMarket(guildId) } returns market(3.14)

        val view = service.getTitlesForGuild(guildId, discordId)

        assertEquals(47L, view.tobyCoins)
        assertEquals(3.14, view.marketPrice, 1e-9)
        assertEquals(123L, view.balance)
    }

    @Test
    fun `sell failure from trade service is propagated as Error`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 1_000L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)
        every { tradeService.sell(any(), any(), any()) } returns TradeOutcome.InvalidAmount

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("Sell failed"))
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }
}
