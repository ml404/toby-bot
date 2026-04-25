package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HighlowTest {

    @Test
    fun `play returns cards in 1 to deckSize`() {
        val highlow = Highlow()
        val rng = Random(42)
        repeat(1_000) {
            val hand = highlow.play(Highlow.Direction.HIGHER, rng)
            assertTrue(hand.anchor in 1..highlow.deckSizeValue)
            assertTrue(hand.next in 1..highlow.deckSizeValue)
        }
    }

    @Test
    fun `HIGHER wins when next greater than anchor`() {
        // Force a deterministic sequence: anchor=5, next=10
        val rng = mockSequenceRng(intArrayOf(5, 10))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.HIGHER, rng)
        assertEquals(5, hand.anchor)
        assertEquals(10, hand.next)
        assertTrue(hand.isWin)
        assertEquals(2L, hand.multiplier)
    }

    @Test
    fun `HIGHER loses on tie`() {
        val rng = mockSequenceRng(intArrayOf(7, 7))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.HIGHER, rng)
        assertEquals(7, hand.anchor)
        assertEquals(7, hand.next)
        assertFalse(hand.isWin, "tie loses")
        assertEquals(0L, hand.multiplier)
    }

    @Test
    fun `LOWER wins when next less than anchor`() {
        val rng = mockSequenceRng(intArrayOf(10, 3))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.LOWER, rng)
        assertTrue(hand.isWin)
    }

    @Test
    fun `LOWER loses on tie`() {
        val rng = mockSequenceRng(intArrayOf(8, 8))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.LOWER, rng)
        assertFalse(hand.isWin)
    }

    @Test
    fun `RTP across 200k hands is within 5pp of 12 over 13`() {
        // With tie-loses and a 2x payout, expected RTP is 12/13 ≈ 0.923.
        val highlow = Highlow(deckSize = 13, multiplier = 2L)
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0L
        repeat(n) {
            val direction = if (rng.nextBoolean()) Highlow.Direction.HIGHER else Highlow.Direction.LOWER
            val hand = highlow.play(direction, rng)
            totalWagered += stake
            totalReturned += hand.multiplier * stake
        }
        val rtp = totalReturned.toDouble() / totalWagered.toDouble()
        val expected = 12.0 / 13.0
        assertTrue(rtp in (expected - 0.05)..(expected + 0.05), "expected RTP near $expected (±0.05) but saw $rtp")
    }

    /**
     * Returns a Random whose nextInt(from, until) walks through [seq]
     * in order. Anything beyond the sequence falls back to a real RNG.
     * Highlow.play() calls nextInt twice per hand (anchor then next),
     * which matches our 2-element fixtures exactly.
     */
    private fun mockSequenceRng(seq: IntArray): Random {
        return object : Random() {
            private var i = 0
            override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
            override fun nextInt(from: Int, until: Int): Int {
                return if (i < seq.size) seq[i++] else Random.Default.nextInt(from, until)
            }
        }
    }
}
