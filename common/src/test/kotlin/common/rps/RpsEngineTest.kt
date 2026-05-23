package common.rps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Truth table for [RpsEngine.resolve]. Pure-function unit tests — no
 * mocks. Catches a sign flip in the win matrix (e.g. swapping rock and
 * paper) immediately.
 */
class RpsEngineTest {

    @Test
    fun `rock beats scissors`() {
        assertEquals(RpsEngine.Outcome.FirstWins, RpsEngine.resolve(RpsEngine.Choice.ROCK, RpsEngine.Choice.SCISSORS))
        assertEquals(RpsEngine.Outcome.SecondWins, RpsEngine.resolve(RpsEngine.Choice.SCISSORS, RpsEngine.Choice.ROCK))
    }

    @Test
    fun `paper beats rock`() {
        assertEquals(RpsEngine.Outcome.FirstWins, RpsEngine.resolve(RpsEngine.Choice.PAPER, RpsEngine.Choice.ROCK))
        assertEquals(RpsEngine.Outcome.SecondWins, RpsEngine.resolve(RpsEngine.Choice.ROCK, RpsEngine.Choice.PAPER))
    }

    @Test
    fun `scissors beats paper`() {
        assertEquals(RpsEngine.Outcome.FirstWins, RpsEngine.resolve(RpsEngine.Choice.SCISSORS, RpsEngine.Choice.PAPER))
        assertEquals(RpsEngine.Outcome.SecondWins, RpsEngine.resolve(RpsEngine.Choice.PAPER, RpsEngine.Choice.SCISSORS))
    }

    @Test
    fun `same move is always a draw`() {
        for (choice in RpsEngine.Choice.entries) {
            assertEquals(
                RpsEngine.Outcome.Draw,
                RpsEngine.resolve(choice, choice),
                "$choice vs $choice must draw",
            )
        }
    }

    @Test
    fun `every non-draw resolves to exactly one winner`() {
        // Exhaustive — there are only 9 combinations. Every off-diagonal
        // cell must be a single-winner outcome (never the third value).
        for (first in RpsEngine.Choice.entries) {
            for (second in RpsEngine.Choice.entries) {
                val outcome = RpsEngine.resolve(first, second)
                if (first == second) {
                    assertEquals(RpsEngine.Outcome.Draw, outcome)
                } else {
                    assert(outcome == RpsEngine.Outcome.FirstWins || outcome == RpsEngine.Outcome.SecondWins) {
                        "$first vs $second produced $outcome — expected exactly one winner"
                    }
                }
            }
        }
    }
}
