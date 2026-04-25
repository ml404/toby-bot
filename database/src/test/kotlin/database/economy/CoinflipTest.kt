package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CoinflipTest {

    @Test
    fun `flip returns landed side and predicted side back unchanged`() {
        val coinflip = Coinflip()
        val pull = coinflip.flip(Coinflip.Side.HEADS, Random(0))
        assertEquals(Coinflip.Side.HEADS, pull.predicted)
        // landed is one of the two sides — exact value depends on RNG.
        assertTrue(pull.landed == Coinflip.Side.HEADS || pull.landed == Coinflip.Side.TAILS)
    }

    @Test
    fun `match yields configured multiplier and isWin = true`() {
        val coinflip = Coinflip(multiplier = 2L)
        // Find a seed whose first nextBoolean() is true (HEADS).
        val rngHeads = Random(0)
        val flip = coinflip.flip(predicted = if (rngHeads.nextBoolean()) Coinflip.Side.HEADS else Coinflip.Side.TAILS, random = Random(0))
        assertTrue(flip.isWin)
        assertEquals(2L, flip.multiplier)
    }

    @Test
    fun `mismatch yields zero multiplier and isWin = false`() {
        val coinflip = Coinflip(multiplier = 2L)
        // Pick the OPPOSITE of what the seed will draw.
        val rng = Random(0)
        val landed = if (rng.nextBoolean()) Coinflip.Side.HEADS else Coinflip.Side.TAILS
        val opposite = if (landed == Coinflip.Side.HEADS) Coinflip.Side.TAILS else Coinflip.Side.HEADS
        val flip = coinflip.flip(predicted = opposite, random = Random(0))
        assertFalse(flip.isWin)
        assertEquals(0L, flip.multiplier)
        assertEquals(landed, flip.landed)
        assertEquals(opposite, flip.predicted)
    }

    @Test
    fun `RTP across 200k flips is within 5pp of 1_0`() {
        // No house edge by design — fair 50/50 with 2× payout. Across n flips
        // with random predictions, expected return is exactly 1.0 stake. The
        // ±0.05 floor is forgiving for the n=200k draw; the 95% CI is much
        // tighter.
        val coinflip = Coinflip(multiplier = 2L)
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0L
        repeat(n) {
            val predicted = if (rng.nextBoolean()) Coinflip.Side.HEADS else Coinflip.Side.TAILS
            val flip = coinflip.flip(predicted, rng)
            totalWagered += stake
            totalReturned += flip.multiplier * stake
        }
        val rtp = totalReturned.toDouble() / totalWagered.toDouble()
        assertTrue(rtp in 0.95..1.05, "expected RTP near 1.0 (±0.05) but saw $rtp")
    }
}
