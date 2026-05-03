package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class KenoTest {

    private val keno = Keno()

    // --- play() validation ---

    @Test
    fun `play returns null when picks count is below MIN_SPOTS`() {
        assertNull(keno.play(picks = emptySet(), draws = drawsOf(1..20)))
    }

    @Test
    fun `play returns null when picks count is above MAX_SPOTS`() {
        val tooMany = (1..(Keno.MAX_SPOTS + 1)).toSet()
        assertNull(keno.play(picks = tooMany, draws = drawsOf(21..40)))
    }

    @Test
    fun `play returns null when a pick is outside the 1-80 pool`() {
        assertNull(keno.play(picks = setOf(0, 5, 7), draws = drawsOf(1..20)))
        assertNull(keno.play(picks = setOf(5, 7, 81), draws = drawsOf(1..20)))
    }

    @Test
    fun `play returns null when draws count is not exactly DRAWS`() {
        assertNull(keno.play(picks = setOf(5), draws = (1..19).toList()))
        assertNull(keno.play(picks = setOf(5), draws = (1..21).toList()))
    }

    @Test
    fun `play returns null when draws contain duplicates`() {
        val dupes = (1..19).toMutableList().apply { add(1) }
        assertNull(keno.play(picks = setOf(5), draws = dupes))
    }

    @Test
    fun `play returns null when a drawn number is outside the 1-80 pool`() {
        val bad = (1..19).toMutableList().apply { add(99) }
        assertNull(keno.play(picks = setOf(5), draws = bad))
    }

    // --- play() resolution ---

    @Test
    fun `play counts hits as the intersection of picks and draws`() {
        // Picks: 5, 33, 50, 70 (4 spots). Draws cover 5 and 33 → 2 hits;
        // draws come from 1..20 plus 33, none of which contain 50 or 70.
        val draws = (listOf(33) + (1..19)).take(20)
        val hand = keno.play(picks = setOf(5, 33, 50, 70), draws = draws)
        assertNotNull(hand)
        assertEquals(2, hand!!.hits)
    }

    @Test
    fun `play returns the picks sorted ascending so the UI render is stable`() {
        val hand = keno.play(picks = setOf(80, 1, 42, 7), draws = drawsOf(21..40))
        assertNotNull(hand)
        assertEquals(listOf(1, 7, 42, 80), hand!!.picks)
    }

    @Test
    fun `play looks up the multiplier from the paytable`() {
        // 5-spot, 5 hits → 800x per the paytable.
        val draws = (listOf(1, 2, 3, 4, 5) + (6..20)).take(20)
        val hand = keno.play(picks = setOf(1, 2, 3, 4, 5), draws = draws)
        assertNotNull(hand)
        assertEquals(5, hand!!.hits)
        assertEquals(800.0, hand.multiplier, 1e-9)
        assertTrue(hand.isWin)
    }

    @Test
    fun `zero hits yields the zero multiplier and isWin=false`() {
        // Picks 41-45, draws 1-20 → no overlap.
        val hand = keno.play(picks = setOf(41, 42, 43, 44, 45), draws = drawsOf(1..20))
        assertNotNull(hand)
        assertEquals(0, hand!!.hits)
        assertEquals(0.0, hand.multiplier, 1e-9)
        assertFalse(hand.isWin)
    }

    @Test
    fun `multiplierFor returns zero for hit counts that are not in the paytable row`() {
        assertEquals(0.0, keno.multiplierFor(spots = 5, hits = 0), 1e-9)
        assertEquals(0.0, keno.multiplierFor(spots = 5, hits = 1), 1e-9)
        assertEquals(0.0, keno.multiplierFor(spots = 5, hits = 2), 1e-9)
        // Out-of-range hits must not crash — they just pay nothing.
        assertEquals(0.0, keno.multiplierFor(spots = 5, hits = 99), 1e-9)
        assertEquals(0.0, keno.multiplierFor(spots = 99, hits = 5), 1e-9)
    }

    @Test
    fun `maxMultiplier exposes the top payout for a spot count`() {
        assertEquals(3.5, keno.maxMultiplier(1), 1e-9)
        assertEquals(800.0, keno.maxMultiplier(5), 1e-9)
        assertEquals(165_000.0, keno.maxMultiplier(10), 1e-9)
        assertEquals(0.0, keno.maxMultiplier(99), 1e-9)
    }

    // --- drawNumbers ---

    @Test
    fun `drawNumbers returns DRAWS distinct values inside the pool range`() {
        val draws = keno.drawNumbers(Random(0))
        assertEquals(Keno.DRAWS, draws.size)
        assertEquals(Keno.DRAWS, draws.toSet().size)
        assertTrue(draws.all { it in Keno.POOL_RANGE })
    }

    @Test
    fun `drawNumbers is deterministic for a given seed`() {
        val a = Keno().drawNumbers(Random(1234))
        val b = Keno().drawNumbers(Random(1234))
        assertEquals(a, b)
    }

    // --- quickPick ---

    @Test
    fun `quickPick returns count distinct sorted values inside the pool range`() {
        val picks = keno.quickPick(7, Random(0))
        assertEquals(7, picks.size)
        assertEquals(7, picks.toSet().size)
        assertTrue(picks.all { it in Keno.POOL_RANGE })
        assertEquals(picks.sorted(), picks)
    }

    @Test
    fun `quickPick rejects counts outside the spots range`() {
        runCatching { keno.quickPick(0, Random(0)) }.exceptionOrNull().let { assertNotNull(it) }
        runCatching { keno.quickPick(11, Random(0)) }.exceptionOrNull().let { assertNotNull(it) }
    }

    // --- RTP ---

    @Test
    fun `RTP for every spot count lands in the published 0_83 to 0_92 band`() {
        // Hypergeometric expected return: sum over k of P(k|spots) * mult[k].
        // P(k|n) = C(n,k) * C(80-n, 20-k) / C(80, 20).
        val rtps = (Keno.MIN_SPOTS..Keno.MAX_SPOTS).associateWith { spots ->
            (0..spots).sumOf { k ->
                hypergeometricProbability(spots, k) * keno.multiplierFor(spots, k)
            }
        }
        // Print on failure with per-bucket contributions so the maintainer
        // can re-tune individual cells without iterating through reruns.
        val outOfBand = rtps.filterValues { it !in 0.83..0.92 }
        if (outOfBand.isNotEmpty()) {
            val detail = (Keno.MIN_SPOTS..Keno.MAX_SPOTS).joinToString("\n") { spots ->
                val perK = (0..spots).joinToString(" ") { k ->
                    val p = hypergeometricProbability(spots, k)
                    val m = keno.multiplierFor(spots, k)
                    "k=$k:${"%.4f".format(p * m)}"
                }
                "  $spots-spot rtp=${"%.4f".format(rtps[spots])}  $perK"
            }
            error("RTP out of band 0.83..0.92 — full breakdown:\n$detail")
        }
    }

    @Test
    fun `RTP smoke — 50k random hands stay in the published band`() {
        val rng = Random(2026)
        val stake = 1_000L
        val n = 50_000
        var totalWagered = 0L
        var totalReturned = 0.0
        repeat(n) {
            val spots = (Keno.MIN_SPOTS..Keno.MAX_SPOTS).random(rng)
            val picks = keno.quickPick(spots, rng).toSet()
            val draws = keno.drawNumbers(rng)
            val hand = keno.play(picks, draws)!!
            totalWagered += stake
            totalReturned += hand.multiplier * stake
        }
        val rtp = totalReturned / totalWagered.toDouble()
        // Mixed across spot counts. Loose because top-end multipliers
        // are very rare and one or two big hits can push the average up.
        assertTrue(rtp in 0.70..1.30, "expected mixed-spots RTP in 0.70..1.30 but saw $rtp")
    }

    // --- helpers ---

    /** Build a 20-element draw list from [range] for "happy-path" hand tests. */
    private fun drawsOf(range: IntRange): List<Int> = range.toList().also {
        require(it.size == Keno.DRAWS) { "test draws need exactly ${Keno.DRAWS} elements" }
    }

    /**
     * P(exactly k of n picks hit | 20 drawn from 80) under hypergeometric
     * distribution. Computed in BigInteger via [binomial] to avoid
     * Long overflow on the C(80, 20) denominator (~3.5e18, fits Long but
     * intermediate products don't).
     */
    private fun hypergeometricProbability(spots: Int, hits: Int): Double {
        if (hits < 0 || hits > spots) return 0.0
        if (Keno.DRAWS - hits < 0 || Keno.DRAWS - hits > Keno.POOL_SIZE - spots) return 0.0
        val numerator = binomial(spots, hits) * binomial(Keno.POOL_SIZE - spots, Keno.DRAWS - hits)
        val denominator = binomial(Keno.POOL_SIZE, Keno.DRAWS)
        return numerator.toDouble() / denominator.toDouble()
    }

    private fun binomial(n: Int, k: Int): java.math.BigInteger {
        if (k < 0 || k > n) return java.math.BigInteger.ZERO
        var result = java.math.BigInteger.ONE
        for (i in 0 until k) {
            result = result.multiply(java.math.BigInteger.valueOf((n - i).toLong()))
            result = result.divide(java.math.BigInteger.valueOf((i + 1).toLong()))
        }
        return result
    }
}
