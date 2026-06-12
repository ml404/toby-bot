package web.service

import database.dto.guild.TitleDto
import database.dto.economy.TobyCoinMarketDto
import database.dto.user.UserDto
import common.economy.TobyCoinEngine
import database.service.economy.EconomyTradeService
import database.service.guild.TitleService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
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
import web.util.GuildMembership
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
        // Default the per-guild sell fee to the engine's 1% fallback so
        // the slippage/fee maths in these tests match the historical
        // hardcoded behaviour. Individual tests can override.
        every { tradeService.sellFeeRate(guildId) } returns TobyCoinEngine.TRADE_FEE
        every { jda.getGuildById(guildId) } returns guild

        service = TitlesWebService(
            jda = jda,
            userService = userService,
            titleService = titleService,
            titleRoleService = mockk(relaxed = true),
            introWebService = mockk(relaxed = true),
            marketService = marketService,
            tradeService = tradeService,
            membership = GuildMembership(jda),
        )
    }

    private fun market(price: Double) = TobyCoinMarketDto(guildId = guildId, price = price, lastTickAt = Instant.now())

    @Test
    fun `pure-TOBY path sells enough coins to cover the shortfall under midpoint pricing`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            // ceil(500/2.5) = 200, but the proceeds-after-fee math
            // (midpoint slippage + 1 % jackpot fee) needs N=205 to
            // clear the cost. The compensator bumps until net proceeds
            // ≥ shortfall.
            tobyCoins = 1_000L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        // Multi-coin top-up now liquidates via sellToCover; here it sells TOBY.
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } answers {
            actor.socialCredit = 502L  // proceeds after slippage + 1% fee
            actor.tobyCoins -= 205L
            EconomyTradeService.SellToCoverResult(
                creditsRaised = 502L, covered = true, tobyCoinsSold = 205L, tobyNewPrice = 2.44875, capacity = 2_000L,
            )
        }

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val ok = assertInstanceOf(BuyWithTobyOutcome.Ok::class.java, outcome)
        assertEquals(205L, ok.soldTobyCoins, "the TOBY leg sold surfaces as soldTobyCoins")
        assertEquals(2L, ok.newCredits, "credits = 502 raised − 500 cost")
        assertEquals(2.44875, ok.newPrice, 1e-9)
        verify(exactly = 1) {
            tradeService.sellToCover(discordId, guildId, 500L, any())
        }
        verify(exactly = 1) { titleService.recordPurchase(discordId, 9L) }
    }

    @Test
    fun `top-up path sells enough to cover the shortfall under midpoint pricing`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 200L
            tobyCoins = 100L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        // shortfall = 500 − 200 = 300; sellToCover raises it (here from TOBY).
        every { tradeService.sellToCover(discordId, guildId, 300L, any()) } answers {
            actor.socialCredit = (actor.socialCredit ?: 0L) + 306L
            actor.tobyCoins -= 31L
            EconomyTradeService.SellToCoverResult(
                creditsRaised = 306L, covered = true, tobyCoinsSold = 31L, tobyNewPrice = 9.969, capacity = 5_000L,
            )
        }

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val ok = assertInstanceOf(BuyWithTobyOutcome.Ok::class.java, outcome)
        assertEquals(31L, ok.soldTobyCoins)
        assertEquals(6L, ok.newCredits, "200 + 306 − 500 = 6 (fee + slippage land in the leftover credits)")
        assertEquals(69L, ok.newCoins, "100 TOBY − 31 sold")
        verify(exactly = 1) {
            tradeService.sellToCover(discordId, guildId, 300L, any())
        }
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
        verify(exactly = 0) { tradeService.sell(any(), any(), any(), any()) }
        verify(exactly = 0) { marketService.getMarketForUpdate(any()) }
    }

    @Test
    fun `uncoverable shortfall reports credit shortfall vs max raisable without touching state`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 5L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        // Selling everything raises only 12 credits — short of the 500 shortfall.
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
            EconomyTradeService.SellToCoverResult(0L, covered = false, 0L, null, capacity = 12L)

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val insufficient = assertInstanceOf(BuyWithTobyOutcome.InsufficientCoins::class.java, outcome)
        assertEquals(500L, insufficient.needed, "needed is the credit shortfall")
        assertEquals(12L, insufficient.have, "have is the max raisable across all coins")
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
        verify(exactly = 0) { tradeService.sell(any(), any(), any(), any()) }
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
    fun `getTitlesForGuild surfaces requiredLevel per entry and actorLevel for the viewer`() {
        // Cumulative XP to reach lvl 5 is 100+155+220+295+380 = 1150,
        // so xp=1200 puts the actor at level 5 exactly.
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 50L
            tobyCoins = 0L
            xp = 1_200L
        }
        every { titleService.listAll() } returns listOf(
            TitleDto(id = 1L, label = "Free", cost = 10L, requiredLevel = 0),
            TitleDto(id = 2L, label = "Gated", cost = 200L, requiredLevel = 10),
        )
        every { titleService.listOwned(discordId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns actor
        every { marketService.getMarket(guildId) } returns market(0.0)

        val view = service.getTitlesForGuild(guildId, discordId)

        assertEquals(5, view.actorLevel)
        val gated = view.catalog.first { it.id == 2L }
        assertEquals(10, gated.requiredLevel)
        val free = view.catalog.first { it.id == 1L }
        assertEquals(0, free.requiredLevel)
    }

    @Test
    fun `buyTitle returns level-locked message and does not deduct credits`() {
        val title = TitleDto(id = 42L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 100_000L
            xp = 0L
        }
        every { titleService.getById(42L) } returns title
        every { titleService.owns(discordId, 42L) } returns false
        every { userService.getUserById(discordId, guildId) } returns actor

        val error = service.buyTitle(discordId, guildId, 42L)

        assertTrue(error != null && error.contains("Requires Level 5"))
        assertEquals(100_000L, actor.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buyTitle succeeds when actor level meets requiredLevel (fallback purchase path)`() {
        // cumulative XP to reach level 5 is 1150
        val title = TitleDto(id = 42L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 500L
            xp = 1_200L
        }
        every { titleService.getById(42L) } returns title
        every { titleService.owns(discordId, 42L) } returns false
        every { userService.getUserById(discordId, guildId) } returns actor

        val error = service.buyTitle(discordId, guildId, 42L)

        assertEquals(null, error)
        assertEquals(300L, actor.socialCredit)
        verify(exactly = 1) { userService.updateUser(actor) }
        verify(exactly = 1) { titleService.recordPurchase(discordId, 42L) }
    }

    @Test
    fun `buyTitleWithTobyCoin returns LevelLocked without selling TOBY or touching market`() {
        val title = TitleDto(id = 42L, label = "🌱 Sprout", cost = 200L, requiredLevel = 5)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 1_000L
            xp = 0L
        }
        every { titleService.getById(42L) } returns title
        every { titleService.owns(discordId, 42L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 42L)

        val locked = assertInstanceOf(BuyWithTobyOutcome.LevelLocked::class.java, outcome)
        assertEquals(5, locked.required)
        assertEquals(0, locked.actor)
        verify(exactly = 0) { marketService.getMarketForUpdate(any()) }
        verify(exactly = 0) { tradeService.sell(any(), any(), any(), any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `nothing priced to liquidate is reported as a market Error`() {
        val title = TitleDto(id = 9L, label = "Gold", cost = 500L)
        val actor = UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = 0L
            tobyCoins = 1_000L
        }
        every { titleService.getById(9L) } returns title
        every { titleService.owns(discordId, 9L) } returns false
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns actor
        // Covered=false with zero capacity → no priced market to sell into.
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
            EconomyTradeService.SellToCoverResult(0L, covered = false, 0L, null, capacity = 0L)

        val outcome = service.buyTitleWithTobyCoin(discordId, guildId, 9L)

        val err = assertInstanceOf(BuyWithTobyOutcome.Error::class.java, outcome)
        assertTrue(err.message.contains("market"))
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }
}
