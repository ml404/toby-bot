package database.service

import common.economy.TobyCoinEngine
import database.dto.user.UserDto
import database.service.casino.CasinoTopUpHelper
import database.service.casino.TopUpResult
import database.service.economy.EconomyTradeService
import database.service.economy.EconomyTradeService.SellToCoverResult
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The casino auto-top-up now liquidates across all of a player's coins via
 * [EconomyTradeService.sellToCover] (TOBY first), so these mock that method
 * and assert the helper maps its result onto the [TopUpResult] variants the
 * minigame services consume.
 */
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
        every { tradeService.sellFeeRate(guildId) } returns TobyCoinEngine.TRADE_FEE
    }

    private fun userWith(balance: Long, coins: Long): UserDto =
        UserDto(discordId = discordId, guildId = guildId).apply {
            socialCredit = balance
            tobyCoins = coins
        }

    private fun call(currentBalance: Long, stake: Long, user: UserDto = userWith(currentBalance, 1_000L)) =
        CasinoTopUpHelper.ensureCreditsForWager(
            tradeService, marketService, userService,
            user = user, guildId = guildId, currentBalance = currentBalance, stake = stake
        )

    @Test
    fun `no-op ToppedUp when the player already has enough credits`() {
        val result = call(currentBalance = 500L, stake = 500L)
        val topped = assertInstanceOf(TopUpResult.ToppedUp::class.java, result)
        assertEquals(0L, topped.soldCoins)
        verify(exactly = 0) { tradeService.sellToCover(any(), any(), any(), any()) }
    }

    @Test
    fun `covered top-up carries the TOBY leg and the post-sell balance`() {
        val refreshed = userWith(balance = 502L, coins = 795L)
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
                SellToCoverResult(creditsRaised = 502L, covered = true, tobyCoinsSold = 205L, tobyNewPrice = 2.44875, capacity = 2_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns refreshed

        val result = call(currentBalance = 0L, stake = 500L)

        val topped = assertInstanceOf(TopUpResult.ToppedUp::class.java, result)
        assertEquals(refreshed, topped.user, "wager must see the post-sell entity")
        assertEquals(502L, topped.balance)
        assertEquals(205L, topped.soldCoins)
        assertEquals(2.44875, topped.newPrice, 1e-9)
    }

    @Test
    fun `covered by non-TOBY coins reports a zero TOBY leg`() {
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
                SellToCoverResult(creditsRaised = 540L, covered = true, tobyCoinsSold = 0L, tobyNewPrice = null, capacity = 900L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns userWith(540L, 0L)

        val result = call(currentBalance = 0L, stake = 500L, user = userWith(0L, 0L))

        val topped = assertInstanceOf(TopUpResult.ToppedUp::class.java, result)
        assertEquals(0L, topped.soldCoins, "no TOBY sold → no TOBY receipt line")
        assertEquals(540L, topped.balance)
    }

    @Test
    fun `InsufficientCoins in credit terms when selling everything still falls short`() {
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
                SellToCoverResult(creditsRaised = 0L, covered = false, tobyCoinsSold = 0L, tobyNewPrice = null, capacity = 120L)

        val result = call(currentBalance = 0L, stake = 500L)

        val ic = assertInstanceOf(TopUpResult.InsufficientCoins::class.java, result)
        assertEquals(500L, ic.needed, "needed is the credit shortfall")
        assertEquals(120L, ic.have, "have is the max raisable credits")
    }

    @Test
    fun `MarketUnavailable when there is nothing priced to liquidate`() {
        every { tradeService.sellToCover(discordId, guildId, 500L, any()) } returns
                SellToCoverResult(creditsRaised = 0L, covered = false, tobyCoinsSold = 0L, tobyNewPrice = null, capacity = 0L)

        val result = call(currentBalance = 0L, stake = 500L)

        assertEquals(TopUpResult.MarketUnavailable, result)
    }
}
