package common.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PlinkoTest {

    @Test
    fun `drop always lands in a valid bucket with the configured multiplier`() {
        val plinko = Plinko()
        val rng = Random(42)
        Plinko.Risk.entries.forEach { risk ->
            val table = plinko.payoutTable(risk)
            repeat(1_000) {
                val drop = plinko.drop(risk, rng)
                assertTrue(
                    drop.bucket in 0..Plinko.ROWS,
                    "bucket ${drop.bucket} must be in 0..${Plinko.ROWS}"
                )
                assertEquals(
                    table[drop.bucket], drop.multiplier, 1e-9,
                    "multiplier must match the bucket's table entry"
                )
            }
        }
    }

    @Test
    fun `default payout tables are symmetric across the center bucket`() {
        // Symmetry guarantees an unbiased left/right walk: a tuner can't
        // accidentally favour heads-or-tails by editing one half.
        Plinko.Risk.entries.forEach { risk ->
            val table = Plinko.DEFAULT_PAYOUTS.getValue(risk)
            for (k in 0..Plinko.ROWS / 2) {
                assertEquals(
                    table[k], table[Plinko.ROWS - k], 1e-9,
                    "$risk: bucket $k and ${Plinko.ROWS - k} must mirror"
                )
            }
        }
    }

    @Test
    fun `every risk profile RTP is within 0_88 to 0_92`() {
        // Analytic check using the binomial bucket distribution. Tighter
        // than the Monte Carlo bound below so a typo in the payout table
        // surfaces before the slow path runs.
        val totalWeight = (0..Plinko.ROWS).sumOf { binomial(Plinko.ROWS, it) }
        Plinko.Risk.entries.forEach { risk ->
            val table = Plinko.DEFAULT_PAYOUTS.getValue(risk)
            val ev = (0..Plinko.ROWS).sumOf { k ->
                binomial(Plinko.ROWS, k).toDouble() * table[k]
            } / totalWeight.toDouble()
            assertTrue(
                ev in 0.88..0.92,
                "$risk profile RTP $ev must be in [0.88, 0.92]"
            )
        }
    }

    @Test
    fun `RTP across 100k drops is within +- 5 percent of the analytic target`() {
        val plinko = Plinko()
        val rng = Random(2026)
        val stake = 1_000L
        Plinko.Risk.entries.forEach { risk ->
            var totalWagered = 0L
            var totalReturned = 0L
            repeat(100_000) {
                val drop = plinko.drop(risk, rng)
                totalWagered += stake
                totalReturned += (stake * drop.multiplier).toLong()
            }
            val rtp = totalReturned.toDouble() / totalWagered.toDouble()
            assertTrue(
                rtp in 0.84..0.94,
                "$risk: expected RTP near 0.89 (±0.05) across 100k drops but saw $rtp"
            )
        }
    }

    @Test
    fun `outer buckets always pay strictly more than the center bucket`() {
        // Pinning the basic shape — outer buckets exist to be exciting.
        // A retune that flattens or inverts this curve is almost certainly
        // a bug, so block it at test time.
        Plinko.Risk.entries.forEach { risk ->
            val table = Plinko.DEFAULT_PAYOUTS.getValue(risk)
            assertTrue(
                table[0] > table[Plinko.ROWS / 2],
                "$risk: outer bucket ${table[0]} must beat center ${table[Plinko.ROWS / 2]}"
            )
        }
    }

    @Test
    fun `isWin classification matches multiplier strict-greater-than-1`() {
        val plinko = Plinko()
        val rng = Random(1)
        repeat(500) {
            val drop = plinko.drop(Plinko.Risk.MEDIUM, rng)
            assertEquals(drop.multiplier > 1.0, drop.isWin, "isWin must match multiplier > 1.0")
            assertEquals(drop.multiplier == 1.0, drop.isPush, "isPush must match multiplier == 1.0")
            assertEquals(drop.multiplier < 1.0, drop.isLoss, "isLoss must match multiplier < 1.0")
        }
    }

    private fun binomial(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (i in 0 until k) {
            result = result * (n - i) / (i + 1)
        }
        return result
    }
}
