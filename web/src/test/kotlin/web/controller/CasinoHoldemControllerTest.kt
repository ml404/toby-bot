package web.controller

import database.card.Card
import database.card.Rank
import database.card.Suit
import database.poker.CasinoHoldem
import database.poker.CasinoHoldemTable
import database.poker.CasinoHoldemTableRegistry
import database.service.CasinoHoldemService
import database.service.CasinoHoldemService.ActionOutcome
import database.service.CasinoHoldemService.DealOutcome
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.CasinoPageContext
import web.casino.StakeBounds
import web.service.CasinoHoldemWebService
import web.service.EconomyWebService
import java.time.Instant

class CasinoHoldemControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var service: CasinoHoldemService
    private lateinit var webService: CasinoHoldemWebService
    private lateinit var registry: CasinoHoldemTableRegistry
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: CasinoHoldemController

    @BeforeEach
    fun setup() {
        service = mockk(relaxed = true)
        webService = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = CasinoHoldemController(
            service = service,
            webService = webService,
            tableRegistry = registry,
            economyWebService = economyWebService,
            pageContext = pageContext,
            stakeBounds = stakeBounds,
        )
    }

    // --- /deal ---

    @Test
    fun `deal happy path returns 200 with new tableId and balance`() {
        val table = CasinoHoldemTable(
            id = 7L, guildId = guildId, playerDiscordId = discordId, stake = 100L,
        )
        every {
            service.dealSolo(discordId, guildId, 100L, false)
        } returns DealOutcome.Dealt(
            tableId = 7L,
            snapshot = table,
            newBalance = 900L,
        )

        val response = controller.deal(guildId, CasinoHoldemDealRequest(stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(7L, body.tableId)
        assertEquals(900L, body.newBalance)
        assertNull(body.error)
    }

    @Test
    fun `deal InvalidStake maps to 400 with message`() {
        every {
            service.dealSolo(any(), any(), any(), any())
        } returns DealOutcome.InvalidStake(
            min = CasinoHoldem.MIN_STAKE, max = CasinoHoldem.MAX_STAKE
        )

        val response = controller.deal(guildId, CasinoHoldemDealRequest(stake = 1L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
        assertNotNull(response.body!!.error)
    }

    @Test
    fun `deal InsufficientCredits maps to 400`() {
        every {
            service.dealSolo(any(), any(), any(), any())
        } returns DealOutcome.InsufficientCredits(stake = 100L, have = 20L)

        val response = controller.deal(guildId, CasinoHoldemDealRequest(stake = 100L), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `deal returns 403 when caller is not a guild member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.deal(guildId, CasinoHoldemDealRequest(stake = 100L), user)

        assertEquals(403, response.statusCode.value())
    }

    // --- /action ---

    @Test
    fun `action 404 when no active table for this user`() {
        every { webService.findActiveTable(guildId, discordId) } returns null

        val response = controller.action(guildId, CasinoHoldemActionRequest(action = "fold"), user)

        assertEquals(404, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `action FOLD resolves and returns resolved=true`() {
        every { webService.findActiveTable(guildId, discordId) } returns 7L
        val result = CasinoHoldemTable.HandResult(
            playerHole = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES)),
            dealerHole = listOf(Card(Rank.TWO, Suit.HEARTS), Card(Rank.THREE, Suit.DIAMONDS)),
            board = listOf(
                Card(Rank.QUEEN, Suit.SPADES),
                Card(Rank.JACK, Suit.SPADES),
                Card(Rank.TEN, Suit.SPADES),
            ),
            resolution = null,
            folded = true,
            anteStake = 100L,
            callStake = 0L,
            antePayout = 0L,
            callPayout = 0L,
            totalPayout = 0L,
            resolvedAt = Instant.now(),
        )
        every {
            service.applyAction(discordId, guildId, 7L, CasinoHoldem.Action.FOLD)
        } returns ActionOutcome.Resolved(
            tableId = 7L,
            result = result,
            newBalance = 900L,
            jackpotPayout = 0L,
            lossTribute = 10L,
        )

        val response = controller.action(guildId, CasinoHoldemActionRequest(action = "fold"), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.resolved)
        assertEquals(7L, body.tableId)
        assertEquals(900L, body.newBalance)
        assertEquals(10L, body.lossTribute)
    }

    @Test
    fun `action CALL with insufficient credits returns 400`() {
        every { webService.findActiveTable(guildId, discordId) } returns 7L
        every {
            service.applyAction(discordId, guildId, 7L, CasinoHoldem.Action.CALL)
        } returns ActionOutcome.InsufficientCreditsForCall(needed = 200L, have = 20L)

        val response = controller.action(guildId, CasinoHoldemActionRequest(action = "call"), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    @Test
    fun `action with unknown action string returns 400`() {
        every { webService.findActiveTable(guildId, discordId) } returns 7L

        val response = controller.action(guildId, CasinoHoldemActionRequest(action = "raise"), user)

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body!!.ok)
    }

    // --- /state ---

    @Test
    fun `state 404 when no active table`() {
        every { webService.findActiveTable(guildId, discordId) } returns null

        val response = controller.state(guildId, user)

        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `state returns the snapshot when an active table exists`() {
        every { webService.findActiveTable(guildId, discordId) } returns 7L
        val view = CasinoHoldemWebService.TableStateView(
            tableId = 7L,
            guildId = guildId,
            playerDiscordId = discordId.toString(),
            phase = "AWAIT_DECISION",
            stake = 100L,
            callStake = 200L,
            playerHole = listOf("A♠", "K♠"),
            dealerHole = listOf("??", "??"),
            board = listOf("Q♠", "J♠", "10♠"),
            lastResult = null,
        )
        every { webService.snapshot(7L, discordId) } returns view

        val response = controller.state(guildId, user)

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(view, response.body)
    }
}
