package database.service

import common.events.TicTacToeResolvedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Behavioural tests for the TTT-specific branching in [TicTacToeService].
 * The wager primitives ([PvpWagerService.preflightStart] /
 * [PvpWagerService.debitBoth] / [PvpWagerService.payWinner] /
 * [PvpWagerService.refundBoth]) live in PvpWagerService and are
 * covered by [PvpWagerServiceTest]; this suite mocks them and
 * verifies that the three resolve cases (clean win, forfeit walkover,
 * draw) route to the right primitive and the right ResolveOutcome +
 * event.
 */
class TicTacToeServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var pvpWagerService: PvpWagerService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: TicTacToeService

    @BeforeEach
    fun setUp() {
        pvpWagerService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = TicTacToeService(pvpWagerService, eventPublisher)
    }

    // ---- startMatch / acceptMatch are thin pass-throughs ----

    @Test
    fun `startMatch returns whatever preflightStart returns`() {
        val expected = PvpWagerService.StartOutcome.Ok(200L)
        every { pvpWagerService.readStakeBounds(any(), any(), any(), any(), any()) } returns (0L to 500L)
        every { pvpWagerService.preflightStart(initiatorId, opponentId, guildId, 50L, 0L, 500L) } returns expected

        val outcome = service.startMatch(initiatorId, opponentId, guildId, 50L)
        assertEquals(expected, outcome)
    }

    @Test
    fun `acceptMatch returns whatever debitBoth returns`() {
        val expected = PvpWagerService.AcceptOutcome.Ok(50L, 150L)
        every { pvpWagerService.debitBoth(initiatorId, opponentId, guildId, 50L) } returns expected

        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, 50L)
        assertEquals(expected, outcome)
    }

    // ---- resolveMatch: clean win / forfeit / timeout (all the same wager path) ----

    @Test
    fun `resolveMatch with explicit winner routes to payWinner`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(opponentId, win.loserDiscordId)
        assertEquals(150L, win.winnerNewBalance)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 1) { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, "tictactoe:win", 10L) }
        verify(exactly = 0) { pvpWagerService.refundBoth(any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch flips winner and loser when opponent is the explicit winner`() {
        every { pvpWagerService.payWinner(opponentId, initiatorId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(250L, 0L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = opponentId,
        )
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(opponentId, win.winnerDiscordId)
        assertEquals(initiatorId, win.loserDiscordId)
    }

    // ---- resolveMatch: draw ----

    @Test
    fun `resolveMatch with null winner routes to refundBoth and does not publish`() {
        every { pvpWagerService.refundBoth(initiatorId, opponentId, 50L, guildId) } returns
            PvpWagerService.RefundResult(100L, 200L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = null,
        )
        val draw = outcome as TicTacToeService.ResolveOutcome.Draw
        assertEquals(100L, draw.initiatorNewBalance)
        assertEquals(200L, draw.opponentNewBalance)
        verify(exactly = 0) { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<TicTacToeResolvedEvent>()) }
    }

    // ---- resolveMatch: free play ----

    @Test
    fun `resolveMatch with zero stake routes to payWinner (which handles free-play internally)`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 0L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(0L, 0L, 0L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = initiatorId,
        )
        val win = outcome as TicTacToeService.ResolveOutcome.Win
        assertEquals(0L, win.pot)
        assertEquals(10L, win.xpGranted)
    }

    @Test
    fun `resolveMatch with zero stake and null winner returns a free-play draw without payout`() {
        // refundBoth handles the stake=0 short-circuit (returns zeros).
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = null,
        )
        assertTrue(outcome is TicTacToeService.ResolveOutcome.Draw)
        verify(exactly = 0) { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) }
    }

    // ---- defensive: payWinner returns null / unknown winner id ----

    @Test
    fun `resolveMatch surfaces Unknown when payWinner returns null`() {
        every { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) } returns null
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        assertEquals(TicTacToeService.ResolveOutcome.Unknown, outcome)
        verify(exactly = 0) { eventPublisher.publishEvent(any<TicTacToeResolvedEvent>()) }
    }

    @Test
    fun `resolveMatch surfaces Unknown when the winner id isn't in this match`() {
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = 9999L,
        )
        assertEquals(TicTacToeService.ResolveOutcome.Unknown, outcome)
        verify(exactly = 0) { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<TicTacToeResolvedEvent>()) }
    }

    // ---- event publishing ----

    @Test
    fun `resolveMatch publishes TicTacToeResolvedEvent on a clean win`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<TicTacToeResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.loserDiscordId == opponentId &&
                        it.guildId == guildId &&
                        it.stake == 50L &&
                        it.pot == 100L
                }
            )
        }
    }

    @Test
    fun `resolveMatch publishes TicTacToeResolvedEvent on free-play wins too`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 0L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(0L, 0L, 0L, 0L, 10L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = initiatorId,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<TicTacToeResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.stake == 0L &&
                        it.pot == 0L
                }
            )
        }
    }
}
