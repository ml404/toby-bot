package web.service

import common.pvp.connect4.Connect4Engine
import common.pvp.rps.RpsEngine
import common.pvp.tictactoe.TicTacToeEngine
import database.connect4.Connect4SessionRegistry
import database.duel.PendingDuelRegistry
import database.duel.RecentDuelResolutions
import database.pvp.PvpSessionRegistry
import database.rps.RpsSessionRegistry
import database.service.user.UserService
import database.tictactoe.TicTacToeSessionRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Additional coverage for [PvpWebService] — RPS, TicTacToe, Connect4
 * projection methods and parser helpers. The companion-object mappers
 * in [PvpWebService.PvpResolutionOutcome] are tested elsewhere; this
 * file focuses on the instance methods the existing test misses.
 *
 * All registries are mocked with mockk(relaxed=true) so no Spring
 * context, Docker, or network is needed.
 */
class PvpWebServiceMoreTest {

    private val initiatorId = 100L
    private val opponentId = 200L
    private val guildId = 42L
    private val now = Instant.ofEpochSecond(1_700_000_000L)

    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var rpsSessionRegistry: RpsSessionRegistry
    private lateinit var ticTacToeSessionRegistry: TicTacToeSessionRegistry
    private lateinit var connect4SessionRegistry: Connect4SessionRegistry
    private lateinit var userService: UserService
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var recentDuelResolutions: RecentDuelResolutions
    private lateinit var service: PvpWebService

    @BeforeEach
    fun setup() {
        pendingDuelRegistry = mockk(relaxed = true)
        rpsSessionRegistry = mockk(relaxed = true)
        ticTacToeSessionRegistry = mockk(relaxed = true)
        connect4SessionRegistry = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        memberLookup = mockk {
            every { resolveAll(any(), any()) } returns emptyMap()
            every { fallbackName(any()) } answers { "Player ${firstArg<Long>().toString().takeLast(4)}" }
        }
        recentDuelResolutions = mockk {
            every { consumeForInitiator(any(), any()) } returns emptyList()
        }
        service = PvpWebService(
            pendingDuelRegistry, rpsSessionRegistry, ticTacToeSessionRegistry,
            connect4SessionRegistry, userService, memberLookup, recentDuelResolutions
        )
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun makeRpsSession(
        id: Long = 1L,
        state: PvpSessionRegistry.Session.State = PvpSessionRegistry.Session.State.PENDING,
        picks: MutableMap<Long, RpsEngine.Choice> = mutableMapOf(),
    ) = RpsSessionRegistry.Session(
        id = id, guildId = guildId,
        initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
        stake = 50L, createdAt = now,
        picks = picks, state = state,
    )

    private fun makeTttSession(
        id: Long = 10L,
        state: PvpSessionRegistry.Session.State = PvpSessionRegistry.Session.State.PENDING,
        currentTurn: TicTacToeEngine.Mark = TicTacToeEngine.Mark.X,
        board: TicTacToeEngine.Board = TicTacToeEngine.empty(),
        winningLine: List<Int>? = null,
        winner: TicTacToeEngine.Mark? = null,
    ): TicTacToeSessionRegistry.Session {
        val session = TicTacToeSessionRegistry.Session(
            id = id, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = 30L, createdAt = now,
            board = board, currentTurn = currentTurn,
            winningLine = winningLine, winner = winner,
        )
        session.state = state
        return session
    }

    private fun makeC4Session(
        id: Long = 20L,
        state: PvpSessionRegistry.Session.State = PvpSessionRegistry.Session.State.PENDING,
        currentTurn: Connect4Engine.Mark = Connect4Engine.Mark.RED,
        board: Connect4Engine.Board = Connect4Engine.empty(),
        winningLine: List<Int>? = null,
        winner: Connect4Engine.Mark? = null,
        lastDroppedRow: Int? = null,
        lastDroppedCol: Int? = null,
    ): Connect4SessionRegistry.Session {
        val session = Connect4SessionRegistry.Session(
            id = id, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = 20L, createdAt = now,
            board = board, currentTurn = currentTurn,
            winningLine = winningLine, winner = winner,
            lastDroppedRow = lastDroppedRow, lastDroppedCol = lastDroppedCol,
        )
        session.state = state
        return session
    }

    // ─── RPS pending views ────────────────────────────────────────────

    @Test
    fun `rpsPendingForOpponent maps session to RpsPendingView`() {
        val session = makeRpsSession()
        every { rpsSessionRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(session)
        every { memberLookup.resolveAll(guildId, any()) } returns mapOf(
            initiatorId to MemberLookupHelper.MemberDisplay("Alice", "alice.png"),
            opponentId to MemberLookupHelper.MemberDisplay("Bob", null),
        )

        val views = service.rpsPendingForOpponent(opponentId, guildId)

        assertEquals(1, views.size)
        assertEquals(1L, views[0].sessionId)
        assertEquals(initiatorId.toString(), views[0].participants.initiator.discordId)
        assertEquals("Alice", views[0].participants.initiator.name)
        assertEquals("alice.png", views[0].participants.initiator.avatarUrl)
        assertEquals(opponentId.toString(), views[0].participants.opponent.discordId)
        assertEquals("Bob", views[0].participants.opponent.name)
        assertNull(views[0].participants.opponent.avatarUrl)
        assertEquals(50L, views[0].participants.stake)
        assertEquals(now.epochSecond, views[0].participants.createdAtEpochSeconds)
    }

    @Test
    fun `rpsPendingForInitiator maps session to RpsPendingView`() {
        val session = makeRpsSession()
        every { rpsSessionRegistry.pendingForInitiator(initiatorId, guildId) } returns listOf(session)

        val views = service.rpsPendingForInitiator(initiatorId, guildId)

        assertEquals(1, views.size)
        assertEquals(1L, views[0].sessionId)
    }

    @Test
    fun `rpsPendingForOpponent returns empty list when registry returns nothing`() {
        every { rpsSessionRegistry.pendingForOpponent(any(), any()) } returns emptyList()
        assertTrue(service.rpsPendingForOpponent(opponentId, guildId).isEmpty())
    }

    @Test
    fun `rpsPendingForOpponent falls back to Player XXXX when member not resolved`() {
        val session = makeRpsSession()
        every { rpsSessionRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(session)
        every { memberLookup.resolveAll(guildId, any()) } returns emptyMap()

        val views = service.rpsPendingForOpponent(opponentId, guildId)

        assertEquals("Player 100", views[0].participants.initiator.name)
        assertEquals("Player 200", views[0].participants.opponent.name)
        assertNull(views[0].participants.initiator.avatarUrl)
    }

    // ─── RPS active (live) session views ─────────────────────────────

    @Test
    fun `rpsActiveFor returns live sessions projected as RpsSessionView`() {
        val liveSession = makeRpsSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            picks = mutableMapOf(initiatorId to RpsEngine.Choice.ROCK),
        )
        every { rpsSessionRegistry.liveFor(initiatorId, guildId) } returns listOf(liveSession)
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val views = service.rpsActiveFor(initiatorId, guildId)

        assertEquals(1, views.size)
        val v = views[0]
        assertEquals(1L, v.sessionId)
        assertEquals("LIVE", v.state)
        assertTrue(v.iPicked)       // initiator has a pick
        assertFalse(v.opponentPicked)
        assertNotNull(v.expiresAtEpochSeconds)
    }

    @Test
    fun `rpsActiveFor shows iPicked true when viewer has submitted a pick`() {
        // Viewer = opponentId, opponentId has submitted a pick.
        // From opponentId's POV: iPicked=true (I picked), opponentPicked=false (initiator hasn't).
        val liveSession = makeRpsSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            picks = mutableMapOf(opponentId to RpsEngine.Choice.SCISSORS),
        )
        every { rpsSessionRegistry.liveFor(opponentId, guildId) } returns listOf(liveSession)
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val views = service.rpsActiveFor(opponentId, guildId)

        assertEquals(1, views.size)
        // viewer = opponentId, picks contains opponentId → iPicked=true
        assertTrue(views[0].iPicked)
        // the "opponent" from opponentId's perspective is initiatorId (100L), who hasn't picked
        assertFalse(views[0].opponentPicked)
    }

    @Test
    fun `rpsActiveFor shows opponentPicked when initiator has submitted but viewer has not`() {
        // Viewer = opponentId. Initiator (100) already picked, opponent hasn't.
        // From opponentId's POV: iPicked=false, opponentPicked=true (initiatorId = "my opponent").
        val liveSession = makeRpsSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            picks = mutableMapOf(initiatorId to RpsEngine.Choice.ROCK),
        )
        every { rpsSessionRegistry.liveFor(opponentId, guildId) } returns listOf(liveSession)
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val views = service.rpsActiveFor(opponentId, guildId)

        assertEquals(1, views.size)
        assertFalse(views[0].iPicked)       // opponentId hasn't picked
        assertTrue(views[0].opponentPicked) // initiatorId = "opponent" from opponentId's view, has picked
    }

    @Test
    fun `rpsSessionView returns null when session does not exist`() {
        every { rpsSessionRegistry.get(999L) } returns null
        assertNull(service.rpsSessionView(999L, initiatorId))
    }

    @Test
    fun `rpsSessionView returns null when viewer is not a participant`() {
        val session = makeRpsSession()
        every { rpsSessionRegistry.get(1L) } returns session

        assertNull(service.rpsSessionView(1L, viewerDiscordId = 9999L))
    }

    @Test
    fun `rpsSessionView returns view for initiator participant`() {
        val session = makeRpsSession(state = PvpSessionRegistry.Session.State.PENDING)
        every { rpsSessionRegistry.get(1L) } returns session
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val view = service.rpsSessionView(1L, initiatorId)

        assertNotNull(view)
        assertEquals("PENDING", view!!.state)
        // PENDING → expiresAt should be null
        assertNull(view.expiresAtEpochSeconds)
    }

    @Test
    fun `rpsSessionView returns view for opponent participant`() {
        val session = makeRpsSession(state = PvpSessionRegistry.Session.State.LIVE)
        every { rpsSessionRegistry.get(1L) } returns session
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val view = service.rpsSessionView(1L, opponentId)

        assertNotNull(view)
        assertEquals("LIVE", view!!.state)
        // LIVE → expiresAt is now + pickTtl
        assertNotNull(view.expiresAtEpochSeconds)
    }

    // ─── TicTacToe pending views ──────────────────────────────────────

    @Test
    fun `ticTacToePendingForOpponent maps session to TicTacToePendingView`() {
        val session = makeTttSession()
        every { ticTacToeSessionRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(session)
        every { memberLookup.resolveAll(guildId, any()) } returns mapOf(
            initiatorId to MemberLookupHelper.MemberDisplay("Alice", null),
        )

        val views = service.ticTacToePendingForOpponent(opponentId, guildId)

        assertEquals(1, views.size)
        assertEquals(10L, views[0].sessionId)
        assertEquals(initiatorId.toString(), views[0].participants.initiator.discordId)
        assertEquals("Alice", views[0].participants.initiator.name)
        assertEquals(30L, views[0].participants.stake)
    }

    @Test
    fun `ticTacToePendingForInitiator maps session to TicTacToePendingView`() {
        val session = makeTttSession()
        every { ticTacToeSessionRegistry.pendingForInitiator(initiatorId, guildId) } returns listOf(session)

        val views = service.ticTacToePendingForInitiator(initiatorId, guildId)

        assertEquals(1, views.size)
        assertEquals(10L, views[0].sessionId)
    }

    @Test
    fun `ticTacToePendingForOpponent returns empty list when none pending`() {
        every { ticTacToeSessionRegistry.pendingForOpponent(any(), any()) } returns emptyList()
        assertTrue(service.ticTacToePendingForOpponent(opponentId, guildId).isEmpty())
    }

    // ─── TicTacToe active (live) session views ────────────────────────

    @Test
    fun `ticTacToeActiveFor returns live sessions with correct myMark and myTurn`() {
        val liveSession = makeTttSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            currentTurn = TicTacToeEngine.Mark.X,
        )
        every { ticTacToeSessionRegistry.liveFor(initiatorId, guildId) } returns listOf(liveSession)
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val views = service.ticTacToeActiveFor(initiatorId, guildId)

        assertEquals(1, views.size)
        val v = views[0]
        assertEquals("LIVE", v.state)
        assertEquals("X", v.myMark)       // initiator is X
        assertTrue(v.myTurn)              // it's X's turn
        assertEquals(initiatorId.toString(), v.currentActorDiscordId)
        assertNotNull(v.moveExpiresAtEpochSeconds)
    }

    @Test
    fun `ticTacToeActiveFor shows opponentTurn when it is not my turn`() {
        val liveSession = makeTttSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            currentTurn = TicTacToeEngine.Mark.O, // O's turn
        )
        every { ticTacToeSessionRegistry.liveFor(initiatorId, guildId) } returns listOf(liveSession)
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val views = service.ticTacToeActiveFor(initiatorId, guildId)

        assertEquals(1, views.size)
        assertFalse(views[0].myTurn)      // it's O's turn but viewer is X
        assertEquals(opponentId.toString(), views[0].currentActorDiscordId) // O → opponentId
    }

    @Test
    fun `ticTacToeSessionView returns null when session missing`() {
        every { ticTacToeSessionRegistry.get(999L) } returns null
        assertNull(service.ticTacToeSessionView(999L, initiatorId))
    }

    @Test
    fun `ticTacToeSessionView returns null when viewer is not a participant`() {
        val session = makeTttSession()
        every { ticTacToeSessionRegistry.get(10L) } returns session
        assertNull(service.ticTacToeSessionView(10L, 9999L))
    }

    @Test
    fun `ticTacToeSessionView returns view for initiator in PENDING state`() {
        val session = makeTttSession(state = PvpSessionRegistry.Session.State.PENDING)
        every { ticTacToeSessionRegistry.get(10L) } returns session
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.ticTacToeSessionView(10L, initiatorId)

        assertNotNull(view)
        assertEquals("PENDING", view!!.state)
        // PENDING → no current actor
        assertNull(view.currentActorDiscordId)
        assertNull(view.moveExpiresAtEpochSeconds)
    }

    @Test
    fun `ticTacToeSessionView cells list is 9-element null board initially`() {
        val session = makeTttSession(state = PvpSessionRegistry.Session.State.LIVE)
        every { ticTacToeSessionRegistry.get(10L) } returns session
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.ticTacToeSessionView(10L, initiatorId)!!

        assertEquals(9, view.cells.size)
        assertTrue(view.cells.all { it == null })
    }

    @Test
    fun `ticTacToeSessionView exposes winningLine and winner when set`() {
        val session = makeTttSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            winningLine = listOf(0, 1, 2),
            winner = TicTacToeEngine.Mark.X,
        )
        every { ticTacToeSessionRegistry.get(10L) } returns session
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.ticTacToeSessionView(10L, initiatorId)!!

        assertEquals(listOf(0, 1, 2), view.winningLine)
        assertEquals("X", view.winner)
    }

    // ─── Connect4 pending views ───────────────────────────────────────

    @Test
    fun `connect4PendingForOpponent maps session to Connect4PendingView`() {
        val session = makeC4Session()
        every { connect4SessionRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(session)
        every { memberLookup.resolveAll(guildId, any()) } returns mapOf(
            initiatorId to MemberLookupHelper.MemberDisplay("Charlie", "c.png"),
        )

        val views = service.connect4PendingForOpponent(opponentId, guildId)

        assertEquals(1, views.size)
        assertEquals(20L, views[0].sessionId)
        assertEquals("Charlie", views[0].participants.initiator.name)
        assertEquals(20L, views[0].participants.stake)
    }

    @Test
    fun `connect4PendingForInitiator maps session to Connect4PendingView`() {
        val session = makeC4Session()
        every { connect4SessionRegistry.pendingForInitiator(initiatorId, guildId) } returns listOf(session)

        val views = service.connect4PendingForInitiator(initiatorId, guildId)

        assertEquals(1, views.size)
        assertEquals(20L, views[0].sessionId)
    }

    @Test
    fun `connect4PendingForOpponent returns empty list when none pending`() {
        every { connect4SessionRegistry.pendingForOpponent(any(), any()) } returns emptyList()
        assertTrue(service.connect4PendingForOpponent(opponentId, guildId).isEmpty())
    }

    // ─── Connect4 active (live) session views ─────────────────────────

    @Test
    fun `connect4ActiveFor returns live sessions with RED myMark for initiator`() {
        val liveSession = makeC4Session(
            state = PvpSessionRegistry.Session.State.LIVE,
            currentTurn = Connect4Engine.Mark.RED,
        )
        every { connect4SessionRegistry.liveFor(initiatorId, guildId) } returns listOf(liveSession)
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val views = service.connect4ActiveFor(initiatorId, guildId)

        assertEquals(1, views.size)
        val v = views[0]
        assertEquals("LIVE", v.state)
        assertEquals("RED", v.myMark)
        assertTrue(v.myTurn)
        assertEquals(initiatorId.toString(), v.currentActorDiscordId)
    }

    @Test
    fun `connect4ActiveFor shows YELLOW myMark and correct turn for opponent`() {
        val liveSession = makeC4Session(
            state = PvpSessionRegistry.Session.State.LIVE,
            currentTurn = Connect4Engine.Mark.YELLOW,
        )
        every { connect4SessionRegistry.liveFor(opponentId, guildId) } returns listOf(liveSession)
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val views = service.connect4ActiveFor(opponentId, guildId)

        assertEquals(1, views.size)
        assertEquals("YELLOW", views[0].myMark)
        assertTrue(views[0].myTurn)
        assertEquals(opponentId.toString(), views[0].currentActorDiscordId)
    }

    @Test
    fun `connect4SessionView returns null when session missing`() {
        every { connect4SessionRegistry.get(999L) } returns null
        assertNull(service.connect4SessionView(999L, initiatorId))
    }

    @Test
    fun `connect4SessionView returns null when viewer is not a participant`() {
        val session = makeC4Session()
        every { connect4SessionRegistry.get(20L) } returns session
        assertNull(service.connect4SessionView(20L, 9999L))
    }

    @Test
    fun `connect4SessionView returns view for initiator in PENDING state`() {
        val session = makeC4Session(state = PvpSessionRegistry.Session.State.PENDING)
        every { connect4SessionRegistry.get(20L) } returns session
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.connect4SessionView(20L, initiatorId)

        assertNotNull(view)
        assertEquals("PENDING", view!!.state)
        assertNull(view.currentActorDiscordId)
        assertNull(view.moveExpiresAtEpochSeconds)
    }

    @Test
    fun `connect4SessionView cells is 42-element null board initially`() {
        val session = makeC4Session(state = PvpSessionRegistry.Session.State.LIVE)
        every { connect4SessionRegistry.get(20L) } returns session
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.connect4SessionView(20L, initiatorId)!!

        assertEquals(42, view.cells.size) // 7 cols × 6 rows
        assertTrue(view.cells.all { it == null })
    }

    @Test
    fun `connect4SessionView exposes winningLine winner and lastDropped coords when set`() {
        val session = makeC4Session(
            state = PvpSessionRegistry.Session.State.LIVE,
            winningLine = listOf(0, 7, 14, 21),
            winner = Connect4Engine.Mark.RED,
            lastDroppedRow = 5, lastDroppedCol = 3,
        )
        every { connect4SessionRegistry.get(20L) } returns session
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.connect4SessionView(20L, initiatorId)!!

        assertEquals(listOf(0, 7, 14, 21), view.winningLine)
        assertEquals("RED", view.winner)
        assertEquals(5, view.lastDroppedRow)
        assertEquals(3, view.lastDroppedCol)
    }

    @Test
    fun `connect4SessionView has null lastDropped and winningLine on fresh LIVE session`() {
        val session = makeC4Session(state = PvpSessionRegistry.Session.State.LIVE)
        every { connect4SessionRegistry.get(20L) } returns session
        every { connect4SessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.connect4SessionView(20L, initiatorId)!!

        assertNull(view.lastDroppedRow)
        assertNull(view.lastDroppedCol)
        assertNull(view.winningLine)
        assertNull(view.winner)
    }

    // ─── parseCellIndex ───────────────────────────────────────────────

    @Test
    fun `parseCellIndex returns index when in range`() {
        assertEquals(0, service.parseCellIndex(0, 9))
        assertEquals(8, service.parseCellIndex(8, 9))
        assertEquals(6, service.parseCellIndex(6, 7))
    }

    @Test
    fun `parseCellIndex returns null when index is negative`() {
        assertNull(service.parseCellIndex(-1, 9))
    }

    @Test
    fun `parseCellIndex returns null when index equals max`() {
        assertNull(service.parseCellIndex(9, 9))
    }

    @Test
    fun `parseCellIndex returns null when index exceeds max`() {
        assertNull(service.parseCellIndex(10, 9))
    }

    @Test
    fun `parseCellIndex returns null when raw is null`() {
        assertNull(service.parseCellIndex(null, 9))
    }

    // ─── parseRpsChoice ───────────────────────────────────────────────

    @Test
    fun `parseRpsChoice returns ROCK for rock (case insensitive)`() {
        assertEquals(RpsEngine.Choice.ROCK, service.parseRpsChoice("rock"))
        assertEquals(RpsEngine.Choice.ROCK, service.parseRpsChoice("ROCK"))
        assertEquals(RpsEngine.Choice.ROCK, service.parseRpsChoice("Rock"))
    }

    @Test
    fun `parseRpsChoice returns PAPER for paper`() {
        assertEquals(RpsEngine.Choice.PAPER, service.parseRpsChoice("paper"))
        assertEquals(RpsEngine.Choice.PAPER, service.parseRpsChoice("PAPER"))
    }

    @Test
    fun `parseRpsChoice returns SCISSORS for scissors`() {
        assertEquals(RpsEngine.Choice.SCISSORS, service.parseRpsChoice("scissors"))
        assertEquals(RpsEngine.Choice.SCISSORS, service.parseRpsChoice("SCISSORS"))
    }

    @Test
    fun `parseRpsChoice returns null for unknown string`() {
        assertNull(service.parseRpsChoice("lizard"))
        assertNull(service.parseRpsChoice("spock"))
        assertNull(service.parseRpsChoice(""))
    }

    @Test
    fun `parseRpsChoice returns null for null input`() {
        assertNull(service.parseRpsChoice(null))
    }

    // ─── RPS session view — picks bookkeeping ─────────────────────────

    @Test
    fun `rpsSessionView iPicked false and opponentPicked false when neither has picked`() {
        val session = makeRpsSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            picks = mutableMapOf(),
        )
        every { rpsSessionRegistry.get(1L) } returns session
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val view = service.rpsSessionView(1L, initiatorId)!!

        assertFalse(view.iPicked)
        assertFalse(view.opponentPicked)
    }

    @Test
    fun `rpsSessionView both picked when both picks recorded`() {
        val session = makeRpsSession(
            state = PvpSessionRegistry.Session.State.LIVE,
            picks = mutableMapOf(
                initiatorId to RpsEngine.Choice.ROCK,
                opponentId to RpsEngine.Choice.SCISSORS,
            ),
        )
        every { rpsSessionRegistry.get(1L) } returns session
        every { rpsSessionRegistry.pickTtl } returns Duration.ofMinutes(2)

        val view = service.rpsSessionView(1L, initiatorId)!!

        assertTrue(view.iPicked)
        assertTrue(view.opponentPicked)
    }

    // ─── memberLookup.resolveAll called with correct batch for RPS ────

    @Test
    fun `rpsPendingForOpponent batches both participant ids in a single resolveAll call`() {
        val session = makeRpsSession()
        every { rpsSessionRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(session)

        service.rpsPendingForOpponent(opponentId, guildId)

        verify(exactly = 1) { memberLookup.resolveAll(guildId, any()) }
    }

    // ─── myTurn=false in non-LIVE state (TTT) ─────────────────────────

    @Test
    fun `ticTacToeSessionView myTurn is false in PENDING state even if viewer is X`() {
        val session = makeTttSession(
            state = PvpSessionRegistry.Session.State.PENDING,
            currentTurn = TicTacToeEngine.Mark.X,
        )
        every { ticTacToeSessionRegistry.get(10L) } returns session
        every { ticTacToeSessionRegistry.moveTtl } returns Duration.ofMinutes(1)

        val view = service.ticTacToeSessionView(10L, initiatorId)!!

        assertFalse(view.myTurn)
    }

    // ─── connect4 myTurn=false for non-participant (should be null) ───

    @Test
    fun `connect4SessionView myMark is null for non-participant (covered by null guard)`() {
        // The connect4SessionView method guards non-participant viewers with early null return.
        // If somehow a viewer with no mark reaches projectConnect4Session, myMark would be null.
        // We verify the guard: a non-participant gets null back.
        val session = makeC4Session()
        every { connect4SessionRegistry.get(20L) } returns session

        assertNull(service.connect4SessionView(20L, 9999L))
    }
}
