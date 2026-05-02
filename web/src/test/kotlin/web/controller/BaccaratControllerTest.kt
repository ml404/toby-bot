package web.controller

import database.card.Card
import database.card.Rank
import database.card.Suit
import database.economy.Baccarat
import database.service.BaccaratService
import database.service.BaccaratService.PlayOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.service.EconomyWebService

class BaccaratControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var baccaratService: BaccaratService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var user: OAuth2User
    private lateinit var controller: BaccaratController

    @BeforeEach
    fun setup() {
        baccaratService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = BaccaratController(baccaratService, economyWebService, pageContext)
    }

    private fun playerCards() = listOf(Card(Rank.FIVE, Suit.SPADES), Card(Rank.THREE, Suit.HEARTS))
    private fun bankerCards() = listOf(Card(Rank.TWO, Suit.CLUBS), Card(Rank.FOUR, Suit.DIAMONDS))

    @Test
    fun `Player win returns 200 with both hands and win-shaped payload`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 8,
            bankerTotal = 6,
            isPlayerNatural = true,
            isBankerNatural = false,
            multiplier = 2.0,
            newBalance = 1_100L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(false, body.push)
        assertEquals(100L, body.net)
        assertEquals(200L, body.payout)
        assertEquals(1_100L, body.newBalance)
        assertEquals("PLAYER", body.side)
        assertEquals("PLAYER", body.winner)
        assertEquals(2.0, body.multiplier)
        assertEquals(8, body.playerTotal)
        assertEquals(6, body.bankerTotal)
        assertEquals(true, body.isPlayerNatural)
        assertEquals(false, body.isBankerNatural)
        // Cards are serialised as Card.toString() — e.g. "5♠".
        assertEquals(2, body.playerCards?.size)
        assertEquals(2, body.bankerCards?.size)
    }

    @Test
    fun `Banker win pays at the 1_95x commission rate`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.BANKER, false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 195L,
            net = 95L,
            side = Baccarat.Side.BANKER,
            winner = Baccarat.Side.BANKER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 6,
            bankerTotal = 8,
            isPlayerNatural = false,
            isBankerNatural = true,
            multiplier = 1.95,
            newBalance = 1_095L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "BANKER", stake = 100L), user
        )

        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(195L, body.payout)
        assertEquals(95L, body.net)
        assertEquals(1.95, body.multiplier)
    }

    @Test
    fun `loss returns 200 with negative net and win=false`() {
        every {
            baccaratService.play(discordId, guildId, 50L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Lose(
            stake = 50L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.BANKER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 4,
            bankerTotal = 7,
            isPlayerNatural = false,
            isBankerNatural = false,
            newBalance = 950L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 50L), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(false, body.push)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
        assertEquals("BANKER", body.winner)
    }

    @Test
    fun `tied game pushes Player side bet — push=true and stake refunded`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Push(
            stake = 100L,
            side = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 7,
            bankerTotal = 7,
            isPlayerNatural = false,
            isBankerNatural = false,
            newBalance = 1_000L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(false, body.win)
        assertEquals(true, body.push)
        assertEquals("TIE", body.winner)
        assertEquals(0L, body.net)
        assertEquals(100L, body.payout)
        assertEquals(1_000L, body.newBalance)
    }

    @Test
    fun `Tie side bet wins on a tied game at the natural multiplier`() {
        every {
            baccaratService.play(discordId, guildId, 50L, Baccarat.Side.TIE, false)
        } returns PlayOutcome.Win(
            stake = 50L,
            payout = 450L,
            net = 400L,
            side = Baccarat.Side.TIE,
            winner = Baccarat.Side.TIE,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 8,
            bankerTotal = 8,
            isPlayerNatural = true,
            isBankerNatural = true,
            multiplier = 9.0,
            newBalance = 1_400L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "TIE", stake = 50L), user
        )

        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(false, body.push)
        assertEquals(450L, body.payout)
        assertEquals(400L, body.net)
        assertEquals(9.0, body.multiplier)
    }

    @Test
    fun `play is case-insensitive on the side parameter`() {
        every {
            baccaratService.play(discordId, guildId, 10L, Baccarat.Side.BANKER, false)
        } returns PlayOutcome.Lose(
            stake = 10L,
            side = Baccarat.Side.BANKER,
            winner = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 7,
            bankerTotal = 4,
            isPlayerNatural = false,
            isBankerNatural = false,
            newBalance = 990L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "banker", stake = 10L), user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        verify(exactly = 1) {
            baccaratService.play(discordId, guildId, 10L, Baccarat.Side.BANKER, false)
        }
    }

    @Test
    fun `play rejects unknown side with 400`() {
        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "edge", stake = 10L), user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `play returns 400 on insufficient credits`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("100"))
        assertTrue(response.body?.error!!.contains("30"))
    }

    @Test
    fun `play returns 400 on invalid stake`() {
        every {
            baccaratService.play(discordId, guildId, 5L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 5L), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("10"))
        assertTrue(response.body?.error!!.contains("500"))
    }

    @Test
    fun `play rejects with 403 when user is not a member of the guild`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 8,
            bankerTotal = 5,
            isPlayerNatural = true,
            isBankerNatural = false,
            multiplier = 2.0,
            newBalance = 5_100L,
            jackpotPayout = 4_000L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertEquals(4_000L, response.body!!.jackpotPayout)
    }

    @Test
    fun `non-jackpot win does not include jackpotPayout`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 8,
            bankerTotal = 5,
            isPlayerNatural = true,
            isBankerNatural = false,
            multiplier = 2.0,
            newBalance = 1_100L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertNull(response.body!!.jackpotPayout)
    }

    @Test
    fun `lose with loss tribute surfaces lossTribute on the response`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, false)
        } returns PlayOutcome.Lose(
            stake = 100L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.BANKER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 4,
            bankerTotal = 8,
            isPlayerNatural = false,
            isBankerNatural = true,
            newBalance = 900L,
            lossTribute = 10L
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L), user
        )

        assertEquals(10L, response.body!!.lossTribute)
    }

    @Test
    fun `autoTopUp flag is forwarded to the service`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, true)
        } returns PlayOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            side = Baccarat.Side.PLAYER,
            winner = Baccarat.Side.PLAYER,
            playerCards = playerCards(),
            bankerCards = bankerCards(),
            playerTotal = 8,
            bankerTotal = 5,
            isPlayerNatural = true,
            isBankerNatural = false,
            multiplier = 2.0,
            newBalance = 1_100L,
            soldTobyCoins = 25L,
            newPrice = 4.0,
        )

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L, autoTopUp = true), user
        )

        val body = response.body!!
        assertEquals(true, body.win)
        assertEquals(25L, body.soldTobyCoins)
        assertEquals(4.0, body.newPrice)
        verify(exactly = 1) {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, true)
        }
    }

    @Test
    fun `insufficient TOBY for top-up returns 400 with InsufficientCoinsForTopUp`() {
        every {
            baccaratService.play(discordId, guildId, 100L, Baccarat.Side.PLAYER, true)
        } returns PlayOutcome.InsufficientCoinsForTopUp(needed = 50L, have = 5L)

        val response = controller.play(
            guildId, BaccaratPlayRequest(side = "PLAYER", stake = 100L, autoTopUp = true), user
        )

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body?.error!!.contains("50"))
        assertTrue(response.body?.error!!.contains("5"))
    }
}
