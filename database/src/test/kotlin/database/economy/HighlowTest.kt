package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HighlowTest {

    @Test
    fun `play returns anchor in 2 to deckSize-1 and next in 1 to deckSize`() {
        val highlow = Highlow()
        val rng = Random(42)
        repeat(1_000) {
            val hand = highlow.play(Highlow.Direction.HIGHER, rng)
            assertTrue(hand.anchor in 2..(highlow.deckSizeValue - 1)) {
                "anchor ${hand.anchor} outside 2..${highlow.deckSizeValue - 1}"
            }
            assertTrue(hand.next in 1..highlow.deckSizeValue)
        }
    }

    @Test
    fun `HIGHER wins when next greater than anchor and pays the anchor-aware multiplier`() {
        // Force a deterministic sequence: anchor=5, next=10
        val rng = mockSequenceRng(intArrayOf(5, 10))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.HIGHER, rng)
        assertEquals(5, hand.anchor)
        assertEquals(10, hand.next)
        assertTrue(hand.isWin)
        // anchor=5, HIGHER → winning outcomes = 13-5 = 8, multiplier = 12/8 = 1.5
        assertEquals(1.5, hand.multiplier, 1e-9)
    }

    @Test
    fun `HIGHER loses on tie`() {
        val rng = mockSequenceRng(intArrayOf(7, 7))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.HIGHER, rng)
        assertEquals(7, hand.anchor)
        assertEquals(7, hand.next)
        assertFalse(hand.isWin, "tie loses")
        assertEquals(0.0, hand.multiplier, 1e-9)
    }

    @Test
    fun `LOWER wins when next less than anchor and pays the anchor-aware multiplier`() {
        val rng = mockSequenceRng(intArrayOf(10, 3))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.LOWER, rng)
        assertTrue(hand.isWin)
        // anchor=10, LOWER → winning outcomes = 10-1 = 9, multiplier = 12/9 ≈ 1.333…
        assertEquals(12.0 / 9.0, hand.multiplier, 1e-9)
    }

    @Test
    fun `LOWER loses on tie`() {
        val rng = mockSequenceRng(intArrayOf(8, 8))
        val hand = Highlow(deckSize = 13).play(Highlow.Direction.LOWER, rng)
        assertFalse(hand.isWin)
    }

    @Test
    fun `dealAnchor never returns 1 or deckSize`() {
        val highlow = Highlow(deckSize = 13)
        val rng = Random(2026)
        repeat(10_000) {
            val a = highlow.dealAnchor(rng)
            assertTrue(a in 2..12) { "dealt anchor $a outside 2..12" }
        }
    }

    @Test
    fun `resolve uses the supplied anchor and draws next from rng`() {
        // mockSequenceRng emits one value per nextInt — resolve only
        // calls nextInt once (for `next`), so seq=[12] gives next=12.
        val rng = mockSequenceRng(intArrayOf(12))
        val hand = Highlow(deckSize = 13).resolve(anchor = 5, direction = Highlow.Direction.HIGHER, random = rng)
        assertEquals(5, hand.anchor)
        assertEquals(12, hand.next)
        assertTrue(hand.isWin)
    }

    @Test
    fun `resolve with a tie loses regardless of direction`() {
        val anchor = 9
        val rng = mockSequenceRng(intArrayOf(9))
        val hand = Highlow(deckSize = 13).resolve(anchor, Highlow.Direction.LOWER, rng)
        assertEquals(9, hand.next)
        assertFalse(hand.isWin)
        assertEquals(0.0, hand.multiplier, 1e-9)
    }

    @Test
    fun `resolve rejects an out-of-range anchor`() {
        val highlow = Highlow(deckSize = 13)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            highlow.resolve(anchor = 0, direction = Highlow.Direction.HIGHER, random = Random.Default)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            highlow.resolve(anchor = 14, direction = Highlow.Direction.HIGHER, random = Random.Default)
        }
    }

    @Test
    fun `payoutMultiplier is greater than 1 for every dealt anchor and direction`() {
        // dealAnchor only produces anchors in 2..deckSize-1, and for those
        // both HIGHER and LOWER have at least one winning outcome short of
        // the trivial sweep. WagerHelper computes net = multiplier × stake
        // − stake, so any multiplier ≤ 1 yields a "win" that nets ≤ 0.
        // Pin the schedule strictly above 1× across the dealt range.
        val highlow = Highlow()
        val deckSize = highlow.deckSizeValue
        for (anchor in 2..(deckSize - 1)) {
            for (direction in Highlow.Direction.entries) {
                val m = highlow.payoutMultiplier(anchor, direction)
                assertTrue(m > 1.0) {
                    "anchor=$anchor direction=$direction multiplier=$m must be > 1.0"
                }
            }
        }
    }

    @Test
    fun `payoutMultiplier yields RTP of (deckSize-1) over deckSize for every (anchor, direction)`() {
        // Exact algebraic check: payout × winProb = (deckSize-1) / deckSize.
        val highlow = Highlow()
        val deckSize = highlow.deckSizeValue
        val expected = (deckSize - 1).toDouble() / deckSize
        for (anchor in 2..(deckSize - 1)) {
            for (direction in Highlow.Direction.entries) {
                val winningOutcomes = when (direction) {
                    Highlow.Direction.HIGHER -> deckSize - anchor
                    Highlow.Direction.LOWER -> anchor - 1
                }
                val winProb = winningOutcomes.toDouble() / deckSize
                val rtp = highlow.payoutMultiplier(anchor, direction) * winProb
                assertEquals(expected, rtp, 1e-9) {
                    "anchor=$anchor direction=$direction expected RTP $expected but saw $rtp"
                }
            }
        }
    }

    @Test
    fun `RTP across 200k blind hands is within 5pp of 12 over 13`() {
        // Bundled (Discord) flow: player picks direction blind. Per-hand
        // RTP is constant under the new schedule, so the long-run RTP
        // hugs 12/13 ≈ 0.923 just like before.
        val highlow = Highlow(deckSize = 13)
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0.0
        repeat(n) {
            val direction = if (rng.nextBoolean()) Highlow.Direction.HIGHER else Highlow.Direction.LOWER
            val hand = highlow.play(direction, rng)
            totalWagered += stake
            totalReturned += hand.multiplier * stake
        }
        val rtp = totalReturned / totalWagered.toDouble()
        val expected = 12.0 / 13.0
        assertTrue(rtp in (expected - 0.05)..(expected + 0.05), "expected RTP near $expected (±0.05) but saw $rtp")
    }

    @Test
    fun `RTP across 200k optimal-direction hands is within 5pp of 12 over 13`() {
        // Web flow exploit guard: the player can see the anchor first
        // and pick whichever direction has more winning cards. With a
        // flat 2× multiplier this used to push RTP to ~1.42 (player
        // edge); under the anchor-aware schedule both directions share
        // the same EV, so optimal play also lands at 12/13.
        val highlow = Highlow(deckSize = 13)
        val rng = Random(2026)
        val stake = 1_000L
        val n = 200_000
        var totalWagered = 0L
        var totalReturned = 0.0
        repeat(n) {
            val anchor = highlow.dealAnchor(rng)
            // "Optimal" = whichever direction has more winning outcomes.
            val direction = if ((highlow.deckSizeValue - anchor) >= (anchor - 1)) {
                Highlow.Direction.HIGHER
            } else {
                Highlow.Direction.LOWER
            }
            val hand = highlow.resolve(anchor, direction, rng)
            totalWagered += stake
            totalReturned += hand.multiplier * stake
        }
        val rtp = totalReturned / totalWagered.toDouble()
        val expected = 12.0 / 13.0
        assertTrue(
            rtp in (expected - 0.05)..(expected + 0.05),
            "optimal-play RTP must stay near $expected (±0.05) but saw $rtp — house edge regressed"
        )
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
