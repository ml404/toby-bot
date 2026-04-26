package web.controller

import database.poker.PokerEngine
import database.poker.PokerTable
import database.service.PokerService
import database.service.PokerService.ActionOutcome
import database.service.PokerService.BuyInOutcome
import database.service.PokerService.CashOutOutcome
import database.service.PokerService.CreateOutcome
import database.service.PokerService.StartHandOutcome
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.EconomyWebService
import web.service.PokerWebService

class PokerControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val tableId = 7L

    private lateinit var pokerService: PokerService
    private lateinit var pokerWebService: PokerWebService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: PokerController

    @BeforeEach
    fun setup() {
        pokerService = mockk(relaxed = true)
        pokerWebService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = PokerController(pokerService, pokerWebService, economyWebService, userService, jda)
    }

    @Test
    fun `state returns 401 for anonymous request`() {
        val anon = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns null
            every { getAttribute<String>("username") } returns "guest"
        }
        val response = controller.state(guildId, tableId, anon)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `state returns 403 for non-member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false
        val response = controller.state(guildId, tableId, user)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `state returns 404 when table missing`() {
        every { pokerWebService.snapshot(tableId, discordId) } returns null
        val response = controller.state(guildId, tableId, user)
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `state returns 404 when table belongs to another guild`() {
        every { pokerWebService.snapshot(tableId, discordId) } returns sampleView(guildId = 999L)
        val response = controller.state(guildId, tableId, user)
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `state happy path returns the projected snapshot`() {
        val view = sampleView()
        every { pokerWebService.snapshot(tableId, discordId) } returns view
        val response = controller.state(guildId, tableId, user)
        assertEquals(200, response.statusCode.value())
        assertEquals(view, response.body)
    }

    @Test
    fun `create happy path returns table id`() {
        every { pokerService.createTable(discordId, guildId, 200L) } returns CreateOutcome.Ok(tableId = 42L)

        val response = controller.create(guildId, CreateRequest(buyIn = 200L), user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(42L, response.body!!.tableId)
    }

    @Test
    fun `create propagates invalid buy-in as 400`() {
        every { pokerService.createTable(discordId, guildId, 1L) } returns CreateOutcome.InvalidBuyIn(100L, 5000L)

        val response = controller.create(guildId, CreateRequest(buyIn = 1L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `create rejects non-member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.create(guildId, CreateRequest(buyIn = 200L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { pokerService.createTable(any(), any(), any()) }
    }

    @Test
    fun `join happy path returns ok with new balance`() {
        every { pokerService.buyIn(discordId, guildId, tableId, 300L) } returns
            BuyInOutcome.Ok(seatIndex = 1, newBalance = 700L)

        val response = controller.join(guildId, tableId, JoinRequest(buyIn = 300L), user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(700L, response.body!!.newBalance)
    }

    @Test
    fun `join already-seated returns 400`() {
        every { pokerService.buyIn(discordId, guildId, tableId, 300L) } returns BuyInOutcome.AlreadySeated
        val response = controller.join(guildId, tableId, JoinRequest(buyIn = 300L), user)
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `join unknown table returns 404`() {
        every { pokerService.buyIn(discordId, guildId, tableId, 300L) } returns BuyInOutcome.TableNotFound
        val response = controller.join(guildId, tableId, JoinRequest(buyIn = 300L), user)
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `start happy path`() {
        every { pokerService.startHand(discordId, guildId, tableId, any()) } returns
            StartHandOutcome.Ok(handNumber = 5L)
        val response = controller.start(guildId, tableId, user)
        assertEquals(200, response.statusCode.value())
        assertEquals(5L, response.body!!.handNumber)
    }

    @Test
    fun `start by non-host returns 403`() {
        every { pokerService.startHand(discordId, guildId, tableId, any()) } returns StartHandOutcome.NotHost
        val response = controller.start(guildId, tableId, user)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `action with unknown verb returns 400 without calling service`() {
        val response = controller.action(guildId, tableId, ActionRequest(action = "wibble"), user)
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `action fold happy path returns 200 continued`() {
        every {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        } returns ActionOutcome.Continued
        val response = controller.action(guildId, tableId, ActionRequest(action = "fold"), user)
        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }

    @Test
    fun `action raise rejection returns 400 with error reason`() {
        every {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Raise, any())
        } returns ActionOutcome.Rejected(PokerEngine.RejectReason.RAISE_CAP_REACHED)

        val response = controller.action(guildId, tableId, ActionRequest(action = "raise"), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        assertNotNull(response.body!!.error)
    }

    @Test
    fun `action checkcall auto-picks Call when bet owed`() {
        // Synthesise a snapshot where the viewer owes money.
        val table = mockk<PokerTable>()
        every { pokerService.snapshot(tableId) } returns table
        val seat = PokerTable.Seat(discordId = discordId, chips = 1000L, committedThisRound = 0L)
        every { table.seats } returns mutableListOf(seat)
        every { table.currentBet } returns 10L

        every {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Call, any())
        } returns ActionOutcome.Continued

        val response = controller.action(guildId, tableId, ActionRequest(action = "checkcall"), user)
        assertEquals(200, response.statusCode.value())
        verify {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Call, any())
        }
    }

    @Test
    fun `action checkcall auto-picks Check when nothing owed`() {
        val table = mockk<PokerTable>()
        every { pokerService.snapshot(tableId) } returns table
        val seat = PokerTable.Seat(discordId = discordId, chips = 1000L, committedThisRound = 10L)
        every { table.seats } returns mutableListOf(seat)
        every { table.currentBet } returns 10L

        every {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Check, any())
        } returns ActionOutcome.Continued

        val response = controller.action(guildId, tableId, ActionRequest(action = "checkcall"), user)
        assertEquals(200, response.statusCode.value())
        verify {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Check, any())
        }
    }

    @Test
    fun `action handResolved returns ok with pot+rake`() {
        val result = PokerTable.HandResult(
            handNumber = 7L,
            winners = listOf(discordId),
            payoutByDiscordId = mapOf(discordId to 95L),
            pot = 100L,
            rake = 5L,
            board = emptyList(),
            revealedHoleCards = emptyMap(),
            resolvedAt = java.time.Instant.now()
        )
        every {
            pokerService.applyAction(discordId, guildId, tableId, PokerEngine.PokerAction.Fold, any())
        } returns ActionOutcome.HandResolved(result)

        val response = controller.action(guildId, tableId, ActionRequest(action = "fold"), user)

        assertEquals(200, response.statusCode.value())
        assertEquals(7L, response.body!!.handNumber)
        assertEquals(100L, response.body!!.pot)
        assertEquals(5L, response.body!!.rake)
    }

    @Test
    fun `cashout happy path returns ok with chips returned`() {
        every { pokerService.cashOut(discordId, guildId, tableId) } returns
            CashOutOutcome.Ok(chipsReturned = 250L, newBalance = 1250L)
        val response = controller.cashOut(guildId, tableId, user)
        assertEquals(200, response.statusCode.value())
        assertEquals(250L, response.body!!.chipsReturned)
    }

    @Test
    fun `cashout while hand in progress returns 400`() {
        every { pokerService.cashOut(discordId, guildId, tableId) } returns CashOutOutcome.HandInProgress
        val response = controller.cashOut(guildId, tableId, user)
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `cashout missing table returns 404`() {
        every { pokerService.cashOut(discordId, guildId, tableId) } returns CashOutOutcome.TableNotFound
        val response = controller.cashOut(guildId, tableId, user)
        assertEquals(404, response.statusCode.value())
    }

    private fun sampleView(guildId: Long = this.guildId): PokerWebService.TableStateView =
        PokerWebService.TableStateView(
            tableId = tableId,
            guildId = guildId,
            hostDiscordId = discordId,
            phase = "WAITING",
            handNumber = 0L,
            pot = 0L,
            currentBet = 0L,
            raisesThisStreet = 0,
            maxRaisesPerStreet = 4,
            smallBlind = 5L,
            bigBlind = 10L,
            smallBet = 10L,
            bigBet = 20L,
            minBuyIn = 100L,
            maxBuyIn = 5000L,
            maxSeats = 6,
            dealerIndex = 0,
            actorIndex = 0,
            community = emptyList(),
            seats = emptyList(),
            mySeatIndex = null,
            myHoleCards = emptyList(),
            isMyTurn = false,
            canCheck = false,
            canCall = false,
            canRaise = false,
            callAmount = 0L,
            raiseAmount = 0L,
            lastResult = null
        )
}
