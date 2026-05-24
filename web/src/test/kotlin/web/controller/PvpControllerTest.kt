package web.controller

import database.connect4.Connect4SessionRegistry
import database.duel.PendingDuelRegistry
import database.pvp.PvpSessionRegistry
import database.rps.RpsSessionRegistry
import database.service.pvp.connect4.Connect4Service
import database.service.pvp.duel.DuelService
import database.service.pvp.duel.DuelService.AcceptOutcome
import database.service.pvp.duel.DuelService.StartOutcome
import database.service.pvp.rps.RpsService
import database.service.pvp.tictactoe.TicTacToeService
import database.service.user.UserService
import database.tictactoe.TicTacToeSessionRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.oauth2.core.user.OAuth2User
import web.casino.StakeBounds
import web.event.WebDuelOfferedEvent
import web.service.PvpSseService
import web.service.PvpWebService
import web.service.EconomyWebService
import web.service.MemberLookupHelper
import java.time.Instant

class PvpControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val opponentId = 200L
    private val duelId = 99L

    private lateinit var duelService: DuelService
    private lateinit var rpsService: RpsService
    private lateinit var rpsSessionRegistry: RpsSessionRegistry
    private lateinit var ticTacToeService: TicTacToeService
    private lateinit var ticTacToeSessionRegistry: TicTacToeSessionRegistry
    private lateinit var connect4Service: Connect4Service
    private lateinit var connect4SessionRegistry: Connect4SessionRegistry
    private lateinit var pvpWebService: PvpWebService
    private lateinit var pvpSseService: PvpSseService
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var stakeBounds: StakeBounds
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var user: OAuth2User
    private lateinit var controller: PvpController

    @BeforeEach
    fun setup() {
        duelService = mockk(relaxed = true)
        rpsService = mockk(relaxed = true)
        rpsSessionRegistry = mockk(relaxed = true)
        ticTacToeService = mockk(relaxed = true)
        ticTacToeSessionRegistry = mockk(relaxed = true)
        connect4Service = mockk(relaxed = true)
        connect4SessionRegistry = mockk(relaxed = true)
        pvpWebService = mockk(relaxed = true)
        pvpSseService = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true)
        memberLookup = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        every { economyWebService.isMember(opponentId, guildId) } returns true
        controller = PvpController(
            duelService, rpsService, rpsSessionRegistry,
            ticTacToeService, ticTacToeSessionRegistry,
            connect4Service, connect4SessionRegistry,
            pvpWebService, pvpSseService, pendingDuelRegistry,
            economyWebService, userService, jda, eventPublisher, stakeBounds,
            memberLookup
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

        val response = controller.challenge(guildId, ChallengeRequest(opponentId.toString(), 50L), user)

        assertEquals(200, response.statusCode.value())
        assertTrue(response.body!!.ok)
        assertEquals(duelId, response.body!!.duelId)
        verify { pvpWebService.ensureOpponent(opponentId, guildId) }
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
        val response = controller.challenge(guildId, ChallengeRequest(discordId.toString(), 50L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
        verify(exactly = 0) { pendingDuelRegistry.register(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebDuelOfferedEvent>()) }
    }

    @Test
    fun `challenge of non-member opponent returns 400 without registering`() {
        every { economyWebService.isMember(opponentId, guildId) } returns false

        val response = controller.challenge(guildId, ChallengeRequest(opponentId.toString(), 50L), user)

        assertEquals(400, response.statusCode.value())
        assertFalse(response.body!!.ok)
        verify(exactly = 0) { pvpWebService.ensureOpponent(any(), any()) }
        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<WebDuelOfferedEvent>()) }
    }

    @Test
    fun `challenge with bad start outcome returns 400 without registering`() {
        every {
            duelService.startDuel(discordId, opponentId, guildId, 50L)
        } returns StartOutcome.InitiatorInsufficient(have = 5L, needed = 50L)

        val response = controller.challenge(guildId, ChallengeRequest(opponentId.toString(), 50L), user)

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
    fun `outgoingForMe returns the initiator's pending offers and recent resolutions`() {
        val pending = PvpWebService.PendingDuelView(
            duelId = duelId,
            initiatorDiscordId = discordId.toString(),
            initiatorName = "Me",
            initiatorAvatarUrl = null,
            opponentDiscordId = opponentId.toString(),
            opponentName = "Bob",
            opponentAvatarUrl = "https://cdn/bob.png",
            stake = 50L,
            createdAtEpochSeconds = 1_700_000_000L,
        )
        val resolution = PvpWebService.ResolutionView(
            initiatorDiscordId = discordId.toString(),
            initiatorName = "Me",
            initiatorAvatarUrl = null,
            opponentDiscordId = opponentId.toString(),
            opponentName = "Bob",
            opponentAvatarUrl = "https://cdn/bob.png",
            winnerDiscordId = opponentId.toString(),
            pot = 100L,
            lossTribute = 10L,
        )
        val payload = PvpWebService.OutgoingPayload(
            pending = listOf(pending),
            resolutions = listOf(resolution),
        )
        every { pvpWebService.duelOutgoingPayload(discordId, guildId) } returns payload

        val response = controller.outgoingForMe(guildId, user)

        assertEquals(200, response.statusCode.value())
        assertEquals(payload, response.body)
    }

    // ─── RPS happy paths ──────────────────────────────────────────────

    private fun rpsSession(state: PvpSessionRegistry.Session.State = PvpSessionRegistry.Session.State.PENDING) =
        RpsSessionRegistry.Session(
            id = 1L, guildId = guildId,
            initiatorDiscordId = discordId, opponentDiscordId = opponentId,
            stake = 50L, createdAt = Instant.now(),
        ).also { it.state = state }

    @Test
    fun `rpsChallenge happy path registers session and SSE fans out to opponent`() {
        every {
            rpsService.startMatch(discordId, opponentId, guildId, 50L)
        } returns database.service.pvp.PvpWagerService.StartOutcome.Ok(initiatorBalance = 1000L)
        val registered = rpsSession()
        every {
            rpsSessionRegistry.register(guildId, discordId, opponentId, 50L, any(), any())
        } returns registered
        every { pvpWebService.rpsSessionView(1L, opponentId) } returns null

        val response = controller.rpsChallenge(
            guildId,
            ChallengeRequest(opponentDiscordId = opponentId.toString(), stake = 50L),
            user,
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.ok)
        verify { pvpSseService.fanOutToUser(guildId, opponentId, "rps.offered", any()) }
    }

    @Test
    fun `rpsChallenge rejects self-challenge before touching the service`() {
        val response = controller.rpsChallenge(
            guildId,
            ChallengeRequest(opponentDiscordId = discordId.toString(), stake = 10L),
            user,
        )

        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { rpsService.startMatch(any(), any(), any(), any()) }
    }

    @Test
    fun `rpsAccept happy path debits both and SSE fans accepted to both`() {
        val session = rpsSession()
        // Opponent is the one accepting.
        val opponentUser = mockk<OAuth2User> {
            every { getAttribute<String>("id") } returns opponentId.toString()
            every { getAttribute<String>("username") } returns "opp"
        }
        every { rpsSessionRegistry.get(1L) } returns session
        every { rpsSessionRegistry.accept(1L, any()) } returns session
        every {
            rpsService.acceptMatch(discordId, opponentId, guildId, 50L)
        } returns database.service.pvp.PvpWagerService.AcceptOutcome.Ok(
            initiatorNewBalance = 950L, opponentNewBalance = 950L,
        )

        val response = controller.rpsAccept(guildId, 1L, opponentUser)

        assertEquals(200, response.statusCode.value())
        verify { pvpSseService.fanOutToBoth(guildId, discordId, opponentId, "rps.accepted", any()) }
    }

    @Test
    fun `rpsPick by one side fans pick to other but does not resolve`() {
        val session = rpsSession(PvpSessionRegistry.Session.State.LIVE)
        every { rpsSessionRegistry.get(1L) } returns session
        every { pvpWebService.parseRpsChoice("ROCK") } returns common.rps.RpsEngine.Choice.ROCK
        every { rpsSessionRegistry.recordPick(1L, discordId, common.rps.RpsEngine.Choice.ROCK) } returns
            rpsSession(PvpSessionRegistry.Session.State.LIVE).also {
                it.picks[discordId] = common.rps.RpsEngine.Choice.ROCK
            }

        val response = controller.rpsPick(guildId, 1L, RpsPickRequest(choice = "ROCK"), user)

        assertEquals(200, response.statusCode.value())
        assertEquals(true, response.body?.waitingForOpponent)
        verify { pvpSseService.fanOutToUser(guildId, opponentId, "rps.picked", any()) }
        verify(exactly = 0) { rpsSessionRegistry.consumeForResolution(any()) }
    }

    // ─── TTT + C4 self-challenge rejection ────────────────────────────
    //
    // The challenge / accept / decline / cancel / forfeit shapes are
    // shared via the `boardChallenge` / `boardAccept` / `boardCloseOffer`
    // / `boardForfeit` private helpers — exercised by the RPS tests
    // above. These two tests just confirm the per-game wiring picks up
    // the rejection before calling the service.

    @Test
    fun `tttChallenge rejects self-challenge before touching the service`() {
        val response = controller.tttChallenge(
            guildId,
            ChallengeRequest(opponentDiscordId = discordId.toString(), stake = 10L),
            user,
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { ticTacToeService.startMatch(any(), any(), any(), any()) }
    }

    @Test
    fun `c4Challenge rejects self-challenge before touching the service`() {
        val response = controller.c4Challenge(
            guildId,
            ChallengeRequest(opponentDiscordId = discordId.toString(), stake = 10L),
            user,
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { connect4Service.startMatch(any(), any(), any(), any()) }
    }
}
