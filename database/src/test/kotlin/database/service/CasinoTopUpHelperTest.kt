package database.service

import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.service.EconomyTradeService.TradeOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class CasinoTopUpHelperTest {

    private val discordId = 100L
    private val guildId = 200L

    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var userService: UserService

    @BeforeEach
    fun setup() {
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
    }

    private fun userWith(balance: Long, coins: Long): UserDto =
        UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = balance
            tobyCoins = coins
        }

    private fun market(price: Double): TobyCoinMarketDto =
        TobyCoinMarketDto(guildId = guildId, price = price, lastTickAt = Instant.now())

    @Test
    fun `MarketUnavailable when no market row exists`() {
        every { marketService.getMarketForUpdate(guildId) } returns null

        val result = CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = userWith(balance = 0L, coins = 1_000L),
            guildId = guildId, currentBalance = 0L, stake = 100L
        )

        assertEquals(TopUpResult.MarketUnavailable, result)
        verify(exactly = 0) { tradeService.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `MarketUnavailable when price is zero or negative`() {
        every { marketService.getMarketForUpdate(guildId) } returns market(0.0)

        val result = CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = userWith(balance = 0L, coins = 1_000L),
            guildId = guildId, currentBalance = 0L, stake = 100L
        )

        assertEquals(TopUpResult.MarketUnavailable, result)
    }

    @Test
    fun `InsufficientCoins when user holds fewer TOBY than needed`() {
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)

        val result = CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = userWith(balance = 0L, coins = 5L),  // way short of ~205 needed
            guildId = guildId, currentBalance = 0L, stake = 500L
        )

        val ic = assertInstanceOf(TopUpResult.InsufficientCoins::class.java, result)
        assertEquals(5L, ic.have)
        // True needed is computed by TobyCoinEngine — we just sanity-
        // check it's much greater than what the user has.
        assert(ic.needed > 5L) { "needed should reflect the engine's computation, was ${ic.needed}" }
        verify(exactly = 0) { tradeService.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `ToppedUp delegates to tradeService sell with CASINO_TOPUP reason`() {
        val user = userWith(balance = 0L, coins = 1_000L)
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)
        every {
            tradeService.sell(discordId, guildId, any(), EconomyTradeService.REASON_CASINO_TOPUP)
        } answers {
            val sold = thirdArg<Long>()
            user.tobyCoins -= sold
            user.socialCredit = (user.socialCredit ?: 0L) + 502L
            TradeOutcome.Ok(
                amount = sold,
                transactedCredits = 502L,
                newCoins = user.tobyCoins,
                newCredits = user.socialCredit ?: 0L,
                newPrice = 2.44875,
                fee = 5L
            )
        }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val result = CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = user, guildId = guildId, currentBalance = 0L, stake = 500L
        )

        val topped = assertInstanceOf(TopUpResult.ToppedUp::class.java, result)
        assertEquals(502L, topped.balance, "post-topup balance is the user's new credit total")
        assert(topped.soldCoins > 0L) { "soldCoins should be the engine's computed N" }
        assertEquals(2.44875, topped.newPrice, 1e-9)
        verify(exactly = 1) {
            tradeService.sell(discordId, guildId, any(), EconomyTradeService.REASON_CASINO_TOPUP)
        }
    }

    @Test
    fun `ToppedUp returns the post-sell user when re-read succeeds`() {
        val user = userWith(balance = 0L, coins = 1_000L)
        val refreshed = userWith(balance = 502L, coins = 795L)
        every { marketService.getMarketForUpdate(guildId) } returns market(2.5)
        every {
            tradeService.sell(any(), any(), any(), any())
        } returns TradeOutcome.Ok(
            amount = 205L, transactedCredits = 502L,
            newCoins = 795L, newCredits = 502L, newPrice = 2.44875, fee = 5L
        )
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns refreshed

        val result = CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = user, guildId = guildId, currentBalance = 0L, stake = 500L
        )

        val topped = assertInstanceOf(TopUpResult.ToppedUp::class.java, result)
        // The handle returned IS the re-read entity — important so the
        // wager that follows operates on the post-sell balance.
        assertEquals(refreshed, topped.user)
        assertEquals(502L, topped.balance)
    }
}
