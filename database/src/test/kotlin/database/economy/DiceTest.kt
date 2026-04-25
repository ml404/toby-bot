package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DiceTest {

    @Test
    fun `roll lands within 1 to sides`() {
        val dice = Dice()
        val rng = Random(42)
        repeat(1_000) {
            val roll = dice.roll(predicted = 1, random = rng)
            assertTrue(roll.landed in 1..dice.sidesCount, "landed=${roll.landed}")
            assertTrue(roll.multiplier >= 0L)
        }
    }

    @Test
    fun `match yields configured multiplier`() {
        val dice = Dice(sides = 6, multiplier = 5L)
        // Find a seed where the next int is e.g. 4, then predict 4.
        val rng = Random(7)
        val landed = rng.nextInt(1, 7)
        val roll = dice.roll(predicted = landed, random = Random(7))
        assertTrue(roll.isWin)
        assertEquals(5L, roll.multiplier)
        assertEquals(landed, roll.landed)
        assertEquals(landed, roll.predicted)
    }

    @Test
    fun `mismatch yields zero multiplier`() {
        val dice = Dice(sides = 6, multiplier = 5L)
        val rng = Random(7)
        val landed = rng.nextInt(1, 7)
        // Predict something different from landed
        val opposite = if (landed == 1) 2 else 1
        val roll = dice.roll(predicted = opposite, random = Random(7))
        assertFalse(roll.isWin)
        assertEquals(0L, roll.multiplier)
    }

    @Test
    fun `isValidPrediction enforces 1 to sides`() {
        val dice = Dice(sides = 6)
        assertFalse(dice.isValidPrediction(0))
        assertTrue(dice.isValidPrediction(1))
        assertTrue(dice.isValidPrediction(6))
        assertFalse(dice.isValidPrediction(7))
        assertFalse(dice.isValidPrediction(-1))
    }

    @Test
    fun `winning multiplier is greater than 1`() {
        // WagerHelper computes net = multiplier × stake − stake, so a payout
        // of 1× nets zero — a "win" that pays nothing. Pin the default win
        // multiplier strictly above 1× so future tuning can't introduce that
        // boundary bug.
        assertTrue(
            Dice.DEFAULT_MULTIPLIER > 1L,
            "Dice win multiplier ${Dice.DEFAULT_MULTIPLIER} must be > 1 so a correct call nets > 0"
        )
    }

    @Test
    fun `RTP across 200k rolls is within 5pp of 5 over 6`() {
        // 5× payout at 1/6 odds → expected RTP = 5/6 ≈ 0.833. ±5pp floor
        // is forgiving; n=200k 95% CI is much tighter.
        val dice = Dice(sides = 6, multiplier = 5L)
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0L
        repeat(n) {
            val predicted = rng.nextInt(1, 7)
            val roll = dice.roll(predicted, rng)
            totalWagered += stake
            totalReturned += roll.multiplier * stake
        }
        val rtp = totalReturned.toDouble() / totalWagered.toDouble()
        val expected = 5.0 / 6.0
        assertTrue(rtp in (expected - 0.05)..(expected + 0.05), "expected RTP near $expected (±0.05) but saw $rtp")
    }
}
