package web.controller

import database.duel.PendingDuelRegistry
import database.service.DuelService
import database.service.DuelService.AcceptOutcome
import database.service.DuelService.StartOutcome
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.oauth2.core.user.OAuth2User
import web.event.WebDuelOfferedEvent
import web.service.DuelWebService
import web.service.EconomyWebService
import java.time.Instant

class DuelControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val opponentId = 200L
    private val duelId = 99L

    private lateinit var duelService: DuelService
    private lateinit var duelWebService: DuelWebService
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var user: OAuth2User
    private lateinit var controller: DuelController

    @BeforeEach
    fun setup() {
        duelService = mockk(relaxed = true)
        duelWebService = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        every { economyWebService.isMember(opponentId, guildId) } returns true
        controller = DuelController(
            duelService, duelWebService, pendingDuelRegistry,
            economyWebService, userService, jda, eventPublisher
        )
    }

    private fun pendingFor(opponent: Long) = PendingDuelRegistry.PendingDuel(
        id = duelId, guildId = guildId,
        initiatorDiscordId = discordId, opponentDiscordId = opponent,
        stake = 50L, createdAt = Instant.now()
    )

    @Test
    fun `challenge happy path registers in registry and publishes WebDuelOfferedEvent`() {
        every {
            duelService.startDuel(discordId, opponentId, guildId, 50L)
        } returns StartOutcome.Ok(initiatorBalance = 200L)
        every {
            pendingDuelRegistry.register(guildId, discordId, opponentId, 50L, any(), any())
        } returns PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = discordId, opponentDiscordId = opponentId,
            stake = 50L, createdAt = Instant.now()
        )

        val response = controller.challenge(guildId, ChallengeRequest(opponentId, 50L), user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(duelId, response.body!!.duelId)
        verify { duelWebService.ensureOpponent(opponentId, guildId) }
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                WebDuelOfferedEvent(
                    guildId = guildId,
                    duelId = duelId,
                    initiatorDiscordId = discordId,
                    opponentDiscordId = opponentId,
                    stake = 50L
                )
            )
        }
    }

    @Test
    fun `challenge to self returns 400 without registering`() {
        val response = controller.challenge(guildId, ChallengeRequest(discordId, 50L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
        verify(exactly = 0) { pendingDuelRegistry.register(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebDuelOfferedEvent>()) }
    }

    @Test
    fun `challenge of non-member opponent returns 400 without registering`() {
        every { economyWebService.isMember(opponentId, guildId) } returns false

        val response = controller.challenge(guildId, ChallengeRequest(opponentId, 50L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { duelWebService.ensureOpponent(any(), any()) }
        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebDuelOfferedEvent>()) }
    }

    @Test
    fun `challenge with bad start outcome returns 400 without registering`() {
        every {
            duelService.startDuel(discordId, opponentId, guildId, 50L)
        } returns StartOutcome.InitiatorInsufficient(have = 5L, needed = 50L)

        val response = controller.challenge(guildId, ChallengeRequest(opponentId, 50L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { pendingDuelRegistry.register(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebDuelOfferedEvent>()) }
    }

    @Test
    fun `accept happy path resolves via DuelService and returns Win`() {
        every { pendingDuelRegistry.get(duelId) } returns pendingFor(discordId)
        every { pendingDuelRegistry.consumeForAccept(duelId) } returns pendingFor(discordId)
        every {
            duelService.acceptDuel(discordId, discordId, guildId, 50L, any())
        } returns AcceptOutcome.Win(
            winnerDiscordId = discordId, loserDiscordId = 0L,
            stake = 50L, pot = 100L,
            winnerNewBalance = 245L, loserNewBalance = 50L,
            lossTribute = 5L
        )

        val response = controller.accept(guildId, duelId, user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(100L, response.body!!.pot)
        assertEquals(5L, response.body!!.lossTribute)
    }

    @Test
    fun `accept by non-opponent returns 403`() {
        // Offer's opponent is someone OTHER than the requesting user.
        every { pendingDuelRegistry.get(duelId) } returns pendingFor(opponentId + 1)

        val response = controller.accept(guildId, duelId, user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accept of expired offer returns 410`() {
        every { pendingDuelRegistry.get(duelId) } returns null

        val response = controller.accept(guildId, duelId, user)

        assertEquals(410, response.statusCode.value())
        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accept loses the consume race returns 410`() {
        // Offer was visible at the get() probe but vanished before consume.
        every { pendingDuelRegistry.get(duelId) } returns pendingFor(discordId)
        every { pendingDuelRegistry.consumeForAccept(duelId) } returns null

        val response = controller.accept(guildId, duelId, user)

        assertEquals(410, response.statusCode.value())
        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `decline cancels the offer in registry without calling service`() {
        every { pendingDuelRegistry.get(duelId) } returns pendingFor(discordId)
        every { pendingDuelRegistry.cancel(duelId) } returns pendingFor(discordId)

        val response = controller.decline(guildId, duelId, user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        verify(exactly = 0) { duelService.acceptDuel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `decline by non-opponent returns 403`() {
        every { pendingDuelRegistry.get(duelId) } returns pendingFor(opponentId + 1)

        val response = controller.decline(guildId, duelId, user)

        assertEquals(403, response.statusCode.value())
    }

    private fun outgoing() = PendingDuelRegistry.PendingDuel(
        id = duelId, guildId = guildId,
        initiatorDiscordId = discordId, opponentDiscordId = opponentId,
        stake = 50L, createdAt = Instant.now()
    )

    @Test
    fun `cancel cancels the offer when caller is the initiator`() {
        every { pendingDuelRegistry.get(duelId) } returns outgoing()
        every { pendingDuelRegistry.cancel(duelId) } returns outgoing()

        val response = controller.cancel(guildId, duelId, user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
    }

    @Test
    fun `cancel by non-initiator returns 403`() {
        // Offer's initiator is someone OTHER than the requesting user.
        every { pendingDuelRegistry.get(duelId) } returns PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = discordId + 1, opponentDiscordId = opponentId,
            stake = 50L, createdAt = Instant.now()
        )

        val response = controller.cancel(guildId, duelId, user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { pendingDuelRegistry.cancel(any()) }
    }

    @Test
    fun `cancel of expired offer returns 410`() {
        every { pendingDuelRegistry.get(duelId) } returns null

        val response = controller.cancel(guildId, duelId, user)

        assertEquals(410, response.statusCode.value())
    }

    @Test
    fun `outgoingForMe returns the initiator's pending offers`() {
        val view = DuelWebService.PendingDuelView(duelId, discordId, opponentId, 50L)
        every { duelWebService.pendingForInitiator(discordId, guildId) } returns listOf(view)

        val response = controller.outgoingForMe(guildId, user)

        assertEquals(200, response.statusCode.value())
        assertEquals(listOf(view), response.body)
    }
}
