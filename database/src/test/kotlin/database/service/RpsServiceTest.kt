package database.service

import common.events.RpsResolvedEvent
import common.rps.RpsEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Behavioural tests for the RPS-specific branching in [RpsService].
 * The wager primitives ([PvpWagerService.preflightStart] /
 * [PvpWagerService.debitBoth] / [PvpWagerService.payWinner] /
 * [PvpWagerService.refundBoth]) live in PvpWagerService and are
 * covered by [PvpWagerServiceTest]; this suite mocks them and
 * verifies that the four resolve cases (both-picked-clean,
 * both-picked-draw, one-picked-forfeit, neither-picked) route to the
 * right primitive and the right ResolveOutcome + event.
 */
class RpsServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var pvpWagerService: PvpWagerService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: RpsService

    @BeforeEach
    fun setUp() {
        pvpWagerService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        service = RpsService(pvpWagerService, eventPublisher)
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

    // ---- resolveMatch: clean win ----

    @Test
    fun `resolveMatch on first-wins routes to payWinner with the initiator as winner`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
        assertEquals(opponentId, win.loserDiscordId)
        assertEquals(RpsEngine.Choice.ROCK, win.winnerChoice)
        assertEquals(RpsEngine.Choice.SCISSORS, win.loserChoice)
        assertEquals(150L, win.winnerNewBalance)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 1) { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, "rps:win", 10L) }
        verify(exactly = 0) { pvpWagerService.refundBoth(any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch on second-wins flips the winner and loser ids on payWinner`() {
        every { pvpWagerService.payWinner(opponentId, initiatorId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(250L, 0L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.PAPER,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(opponentId, win.winnerDiscordId)
        assertEquals(initiatorId, win.loserDiscordId)
    }

    // ---- resolveMatch: draw ----

    @Test
    fun `resolveMatch on identical-pick routes to refundBoth and does not publish`() {
        every { pvpWagerService.refundBoth(initiatorId, opponentId, 50L, guildId) } returns
            PvpWagerService.RefundResult(100L, 200L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.PAPER,
            opponentChoice = RpsEngine.Choice.PAPER,
        )
        val draw = outcome as RpsService.ResolveOutcome.Draw
        assertEquals(RpsEngine.Choice.PAPER, draw.choice)
        assertEquals(100L, draw.initiatorNewBalance)
        assertEquals(200L, draw.opponentNewBalance)
        verify(exactly = 0) { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<RpsResolvedEvent>()) }
    }

    // ---- resolveMatch: one-side forfeit ----

    @Test
    fun `resolveMatch credits picker when opponent never picked (forfeit)`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = null,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(initiatorId, win.winnerDiscordId)
    }

    @Test
    fun `resolveMatch credits picker when initiator never picked`() {
        every { pvpWagerService.payWinner(opponentId, initiatorId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(250L, 0L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = null,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(opponentId, win.winnerDiscordId)
    }

    // ---- resolveMatch: double-no-pick ----

    @Test
    fun `resolveMatch double-refunds when neither side picked and does not publish`() {
        every { pvpWagerService.refundBoth(initiatorId, opponentId, 50L, guildId) } returns
            PvpWagerService.RefundResult(100L, 200L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = null,
            opponentChoice = null,
        )
        val refund = outcome as RpsService.ResolveOutcome.DoubleRefund
        assertEquals(100L, refund.initiatorNewBalance)
        assertEquals(200L, refund.opponentNewBalance)
        verify(exactly = 0) { eventPublisher.publishEvent(any<RpsResolvedEvent>()) }
    }

    // ---- defensive: payWinner returns null ----

    @Test
    fun `resolveMatch surfaces Unknown when payWinner returns null`() {
        every { pvpWagerService.payWinner(any(), any(), any(), any(), any(), any()) } returns null

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        assertEquals(RpsService.ResolveOutcome.Unknown, outcome)
        verify(exactly = 0) { eventPublisher.publishEvent(any<RpsResolvedEvent>()) }
    }

    // ---- event publishing ----

    @Test
    fun `resolveMatch publishes RpsResolvedEvent on a clean win`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<RpsResolvedEvent> {
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
    fun `resolveMatch publishes RpsResolvedEvent on free-play wins too`() {
        // Free-play wins must still fire the event so `first_rps_win`
        // can unlock for players who never bet a credit.
        every { pvpWagerService.payWinner(initiatorId, opponentId, 0L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(0L, 0L, 0L, 0L, 10L)

        service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<RpsResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.stake == 0L &&
                        it.pot == 0L
                }
            )
        }
    }

    @Test
    fun `resolveMatch with zero stake routes to payWinner (which handles free-play internally)`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 0L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(0L, 0L, 0L, 0L, 10L)
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 0L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = RpsEngine.Choice.SCISSORS,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(0L, win.pot)
        assertEquals(10L, win.xpGranted)
        verify(exactly = 0) { pvpWagerService.refundBoth(any(), any(), any(), any()) }
    }

    @Test
    fun `resolveMatch loser-choice placeholder is the move beaten by the winner-choice`() {
        // Cosmetic: forfeit-win renders "Rock crushes Scissors" — the loser placeholder
        // must be the choice that the winner's choice actually beats.
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.ROCK,
            opponentChoice = null,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertEquals(RpsEngine.Choice.SCISSORS, win.loserChoice)
    }

    @Test
    fun `loser-choice placeholder for PAPER win is ROCK`() {
        every { pvpWagerService.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)
        val outcome = service.resolveMatch(
            initiatorId, opponentId, guildId, stake = 50L,
            initiatorChoice = RpsEngine.Choice.PAPER,
            opponentChoice = null,
        )
        val win = outcome as RpsService.ResolveOutcome.Win
        assertTrue(win.loserChoice == RpsEngine.Choice.ROCK)
    }
}
