package database.boardgame

import database.dto.ConfigDto
import database.service.pvp.PvpWagerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Behavioural tests for the shared turn-based board wager service
 * base. Drives the routing logic through a minimal [TestService]
 * subclass with a [TestEvent] type so the per-game services (TTT, C4)
 * inherit a single, authoritative test of the resolve branching +
 * event publication.
 *
 * Per-game tests only need to verify the per-game knobs (config keys,
 * xp-reason, event type) propagate correctly — covered by tiny
 * follow-on tests in each game's package.
 */
class TurnBasedBoardWagerServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private data class TestEvent(
        val winnerDiscordId: Long, val loserDiscordId: Long,
        val guildId: Long, val stake: Long, val pot: Long,
    )

    private class TestService(
        pvp: PvpWagerService, publisher: ApplicationEventPublisher?,
    ) : TurnBasedBoardWagerService<TestEvent>(pvp, publisher) {
        override val minStakeKey = ConfigDto.Configurations.TICTACTOE_MIN_STAKE
        override val maxStakeKey = ConfigDto.Configurations.TICTACTOE_MAX_STAKE
        override val xpReason: String = "test:win"
        override fun makeEvent(
            winnerDiscordId: Long, loserDiscordId: Long, guildId: Long, stake: Long, pot: Long,
        ): TestEvent = TestEvent(winnerDiscordId, loserDiscordId, guildId, stake, pot)
    }

    private lateinit var pvp: PvpWagerService
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var service: TestService

    @BeforeEach
    fun setUp() {
        pvp = mockk(relaxed = true)
        publisher = mockk(relaxed = true)
        service = TestService(pvp, publisher)
    }

    // ---- pass-through ----

    @Test
    fun `startMatch reads stake bounds and delegates to preflightStart`() {
        val expected = PvpWagerService.StartOutcome.Ok(200L)
        every { pvp.readStakeBounds(any(), any(), any(), any(), any()) } returns (0L to 500L)
        every { pvp.preflightStart(initiatorId, opponentId, guildId, 50L, 0L, 500L) } returns expected

        val outcome = service.startMatch(initiatorId, opponentId, guildId, 50L)
        assertEquals(expected, outcome)
    }

    @Test
    fun `acceptMatch passes straight through to debitBoth`() {
        val expected = PvpWagerService.AcceptOutcome.Ok(50L, 150L)
        every { pvp.debitBoth(initiatorId, opponentId, guildId, 50L) } returns expected

        val outcome = service.acceptMatch(initiatorId, opponentId, guildId, 50L)
        assertEquals(expected, outcome)
    }

    // ---- resolve routing ----

    @Test
    fun `resolveMatch with explicit winner routes to payWinner and publishes the event`() {
        every { pvp.payWinner(initiatorId, opponentId, 50L, guildId, "test:win", any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        val win = outcome as TurnBasedBoardWagerService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(opponentId, win.loserDiscordId)
        assertEquals(100L, win.pot)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 0) { pvp.refundBoth(any(), any(), any(), any()) }
        verify(exactly = 1) {
            publisher.publishEvent(
                match<TestEvent> {
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
    fun `resolveMatch flips winner and loser ids when opponent is the explicit winner`() {
        every { pvp.payWinner(opponentId, initiatorId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(250L, 0L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = opponentId,
        )
        val win = outcome as TurnBasedBoardWagerService.ResolveOutcome.Win
        assertEquals(opponentId, win.winnerDiscordId)
        assertEquals(initiatorId, win.loserDiscordId)
    }

    @Test
    fun `resolveMatch with null winner routes to refundBoth and does not publish`() {
        every { pvp.refundBoth(initiatorId, opponentId, 50L, guildId) } returns
            PvpWagerService.RefundResult(100L, 200L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = null,
        )
        val draw = outcome as TurnBasedBoardWagerService.ResolveOutcome.Draw
        assertEquals(100L, draw.initiatorNewBalance)
        assertEquals(200L, draw.opponentNewBalance)
        verify(exactly = 0) { pvp.payWinner(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { publisher.publishEvent(any<TestEvent>()) }
    }

    @Test
    fun `resolveMatch surfaces Unknown when payWinner returns null`() {
        every { pvp.payWinner(any(), any(), any(), any(), any(), any()) } returns null
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = initiatorId,
        )
        assertEquals(TurnBasedBoardWagerService.ResolveOutcome.Unknown, outcome)
        verify(exactly = 0) { publisher.publishEvent(any<TestEvent>()) }
    }

    @Test
    fun `resolveMatch surfaces Unknown when the winner id isn't in the match`() {
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            winnerDiscordId = 9999L,
        )
        assertEquals(TurnBasedBoardWagerService.ResolveOutcome.Unknown, outcome)
        verify(exactly = 0) { pvp.payWinner(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch with zero stake routes to payWinner (PvpWagerService handles the free-play short-circuit)`() {
        every { pvp.payWinner(initiatorId, opponentId, 0L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(0L, 0L, 0L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = initiatorId,
        )
        val win = outcome as TurnBasedBoardWagerService.ResolveOutcome.Win
        assertEquals(0L, win.pot)
        assertEquals(10L, win.xpGranted)
    }

    @Test
    fun `resolveMatch with zero stake and null winner returns a free-play draw without payout`() {
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            winnerDiscordId = null,
        )
        assertTrue(outcome is TurnBasedBoardWagerService.ResolveOutcome.Draw)
        verify(exactly = 0) { pvp.payWinner(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `xpReason override propagates to payWinner`() {
        every { pvp.payWinner(any(), any(), any(), any(), any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)

        verify(exactly = 1) {
            pvp.payWinner(initiatorId, opponentId, 50L, guildId, "test:win", any())
        }
    }
}
