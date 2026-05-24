package common.casino.wheeloffortune

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import common.casino.wheeloffortune.WheelOfFortune

class WheelOfFortuneTest {

    @Test
    fun `spin always lands on one of the configured multipliers`() {
        val wheel = WheelOfFortune()
        val rng = Random(42)
        val valid = WheelOfFortune.DEFAULT_WEIGHTS.keys
        repeat(1_000) {
            val spin = wheel.spin(pickedMultiplier = 2L, random = rng)
            assertTrue(
                spin.landedMultiplier in valid,
                "landed multiplier ${spin.landedMultiplier} must be in $valid"
            )
        }
    }

    @Test
    fun `pick must be one of the configured multipliers`() {
        val wheel = WheelOfFortune()
        assertThrows(IllegalArgumentException::class.java) {
            wheel.spin(pickedMultiplier = 7L, random = Random(0))
        }
    }

    @Test
    fun `isWin matches landed equals picked`() {
        val wheel = WheelOfFortune()
        val rng = Random(2026)
        repeat(500) {
            val pick = WheelOfFortune.PICKS.random(rng)
            val spin = wheel.spin(pick, rng)
            assertEquals(spin.landedMultiplier == pick, spin.isWin)
        }
    }

    @Test
    fun `per-pick RTPs hover around 0_88 across 200k spins`() {
        // Loose ±0.04 band — the analytic per-pick RTPs span 0.87..0.90;
        // 200k draws keeps the CI well inside.
        val wheel = WheelOfFortune()
        val rng = Random(13)
        val stake = 1_000L
        WheelOfFortune.PICKS.forEach { pick ->
            var totalWagered = 0L
            var totalReturned = 0L
            repeat(200_000) {
                val spin = wheel.spin(pick, rng)
                totalWagered += stake
                if (spin.isWin) totalReturned += pick * stake
            }
            val rtp = totalReturned.toDouble() / totalWagered.toDouble()
            assertTrue(
                rtp in 0.84..0.94,
                "pick=$pick: expected RTP near 0.88 (±0.04) across 200k spins but saw $rtp"
            )
        }
    }

    @Test
    fun `analytic per-pick RTPs sit within 0_86 to 0_91`() {
        // Closed-form check on the default weight table — drift here means
        // a future retune slipped the canonical RTP outside the band the
        // JackpotGame enum advertises (0.88).
        val totalSlots = WheelOfFortune.DEFAULT_WEIGHTS.values.sum().toDouble()
        WheelOfFortune.DEFAULT_WEIGHTS.forEach { (mult, weight) ->
            val rtp = weight.toDouble() / totalSlots * mult.toDouble()
            assertTrue(
                rtp in 0.86..0.91,
                "pick=$mult analytic RTP=$rtp must be in [0.86, 0.91]"
            )
        }
    }

    @Test
    fun `larger multipliers carry strictly smaller weight than smaller multipliers`() {
        // Wheel design principle: rarer payouts are bigger. If a retune
        // ever inverts this, the per-pick RTPs explode (10× becoming as
        // likely as 2× would make picking 10× a 4.4 RTP no-brainer).
        val ordered = WheelOfFortune.DEFAULT_WEIGHTS.entries.sortedBy { it.key }
        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]
            val curr = ordered[i]
            assertTrue(
                curr.value < prev.value,
                "weight for ${curr.key}× (${curr.value}) must be < weight for ${prev.key}× (${prev.value})"
            )
        }
    }

    @Test
    fun `seeded RNG produces deterministic landings`() {
        val wheelA = WheelOfFortune()
        val wheelB = WheelOfFortune()
        val landingsA = (1..50).map { wheelA.spin(2L, Random(7)).landedMultiplier }
        val landingsB = (1..50).map { wheelB.spin(2L, Random(7)).landedMultiplier }
        assertEquals(landingsA, landingsB, "same seed must produce same wheel landings")
        val landingsC = (1..50).map { wheelA.spin(2L, Random(8)).landedMultiplier }
        assertNotEquals(landingsA, landingsC, "different seeds must diverge somewhere in 50 draws")
    }
}
