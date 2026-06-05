package web.service

import common.pvp.rps.RpsEngine
import database.boardgame.TurnBasedBoardWagerService
import database.service.pvp.rps.RpsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-mapping coverage for [PvpWebService.PvpResolutionOutcome]'s companion
 * factories — the per-game `ResolveOutcome` sealed types translated to the
 * single wire shape the web/SSE layer emits.
 */
class PvpResolutionOutcomeTest {

    private val initiator = 100L
    private val opponent = 200L

    private fun rpsWin(winner: Long, loser: Long) = RpsService.ResolveOutcome.Win(
        winnerDiscordId = winner,
        loserDiscordId = loser,
        winnerChoice = RpsEngine.Choice.ROCK,
        loserChoice = RpsEngine.Choice.SCISSORS,
        stake = 50L,
        pot = 100L,
        winnerNewBalance = 1_050L,
        loserNewBalance = 950L,
        lossTribute = 5L,
        xpGranted = 3L,
    )

    @Test
    fun `rpsWin orients the choices from the initiator's perspective when initiator won`() {
        val o = PvpWebService.PvpResolutionOutcome.rpsWin(rpsWin(initiator, opponent), initiator)
        assertEquals("WIN", o.verdict)
        assertEquals(initiator.toString(), o.winnerDiscordId)
        assertEquals(opponent.toString(), o.loserDiscordId)
        assertEquals("ROCK", o.initiatorChoice) // winner's choice
        assertEquals("SCISSORS", o.opponentChoice) // loser's choice
        assertEquals(5L, o.lossTribute)
        assertEquals(100L, o.pot)
    }

    @Test
    fun `rpsWin orients the choices when the initiator lost`() {
        val o = PvpWebService.PvpResolutionOutcome.rpsWin(rpsWin(opponent, initiator), initiator)
        assertEquals(opponent.toString(), o.winnerDiscordId)
        assertEquals("SCISSORS", o.initiatorChoice) // initiator was the loser
        assertEquals("ROCK", o.opponentChoice)
    }

    @Test
    fun `rpsDraw refunds both with the shared choice`() {
        val o = PvpWebService.PvpResolutionOutcome.rpsDraw(
            RpsService.ResolveOutcome.Draw(RpsEngine.Choice.PAPER, stake = 25L, initiatorNewBalance = 990L, opponentNewBalance = 980L),
        )
        assertEquals("DRAW", o.verdict)
        assertNull(o.winnerDiscordId)
        assertEquals(0L, o.pot)
        assertEquals("PAPER", o.initiatorChoice)
        assertEquals("PAPER", o.opponentChoice)
        assertEquals(990L, o.initiatorNewBalance)
    }

    @Test
    fun `rpsDoubleRefund has no choices`() {
        val o = PvpWebService.PvpResolutionOutcome.rpsDoubleRefund(
            RpsService.ResolveOutcome.DoubleRefund(stake = 25L, initiatorNewBalance = 1_000L, opponentNewBalance = 1_000L),
        )
        assertEquals("REFUND", o.verdict)
        assertNull(o.initiatorChoice)
        assertNull(o.opponentChoice)
    }

    @Test
    fun `fromRps dispatches each variant and returns null for Unknown`() {
        assertEquals("WIN", PvpWebService.PvpResolutionOutcome.fromRps(rpsWin(initiator, opponent), initiator)?.verdict)
        assertEquals(
            "DRAW",
            PvpWebService.PvpResolutionOutcome.fromRps(
                RpsService.ResolveOutcome.Draw(RpsEngine.Choice.ROCK, 10L, 1L, 2L), initiator,
            )?.verdict,
        )
        assertEquals(
            "REFUND",
            PvpWebService.PvpResolutionOutcome.fromRps(
                RpsService.ResolveOutcome.DoubleRefund(10L, 1L, 2L), initiator,
            )?.verdict,
        )
        assertNull(PvpWebService.PvpResolutionOutcome.fromRps(RpsService.ResolveOutcome.Unknown, initiator))
    }

    @Test
    fun `boardWin maps a turn-based win with no choices`() {
        val o = PvpWebService.PvpResolutionOutcome.boardWin(
            TurnBasedBoardWagerService.ResolveOutcome.Win(
                winnerDiscordId = initiator, loserDiscordId = opponent,
                stake = 40L, pot = 80L, winnerNewBalance = 1_040L, loserNewBalance = 960L, lossTribute = 4L, xpGranted = 2L,
            ),
        )
        assertEquals("WIN", o.verdict)
        assertEquals(initiator.toString(), o.winnerDiscordId)
        assertEquals(80L, o.pot)
        assertNull(o.initiatorChoice)
    }

    @Test
    fun `fromBoard dispatches each variant and returns null for Unknown`() {
        assertEquals(
            "WIN",
            PvpWebService.PvpResolutionOutcome.fromBoard(
                TurnBasedBoardWagerService.ResolveOutcome.Win(initiator, opponent, 40L, 80L, 1_040L, 960L, 4L, 2L),
            )?.verdict,
        )
        assertEquals(
            "DRAW",
            PvpWebService.PvpResolutionOutcome.fromBoard(
                TurnBasedBoardWagerService.ResolveOutcome.Draw(stake = 40L, initiatorNewBalance = 1L, opponentNewBalance = 2L),
            )?.verdict,
        )
        assertNull(PvpWebService.PvpResolutionOutcome.fromBoard(TurnBasedBoardWagerService.ResolveOutcome.Unknown))
    }
}
