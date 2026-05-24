package common.casino.horseracing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import common.casino.horseracing.HorseRacing

class HorseRacingTest {

    private val racing = HorseRacing()

    @Test
    fun `field has six horses, indexed 1 through 6, with unique names`() {
        assertEquals(6, HorseRacing.FIELD_SIZE)
        assertEquals(6, HorseRacing.HORSES.size)
        assertEquals((1..6).toList(), HorseRacing.HORSES.map { it.index })
        assertEquals(HorseRacing.HORSES.size, HorseRacing.HORSES.map { it.name }.toSet().size)
    }

    @Test
    fun `win probabilities sum to one exactly so Plackett-Luce strengths are normalised`() {
        val total = HorseRacing.HORSES.sumOf { it.winProb }
        assertTrue(abs(total - 1.0) < 1e-9, "winProb sum was $total")
    }

    @Test
    fun `wins predicate covers exactly the top-N positions for each bet type`() {
        val order = listOf(3, 1, 5, 2, 4, 6)
        // WIN — only horse in position 1 wins.
        (1..6).forEach { h ->
            assertEquals(h == 3, HorseRacing.wins(h, HorseRacing.Bet.WIN, order))
        }
        // PLACE — horses in positions 1 or 2.
        (1..6).forEach { h ->
            assertEquals(h == 3 || h == 1, HorseRacing.wins(h, HorseRacing.Bet.PLACE, order))
        }
        // SHOW — horses in positions 1, 2, or 3.
        (1..6).forEach { h ->
            assertEquals(h == 3 || h == 1 || h == 5, HorseRacing.wins(h, HorseRacing.Bet.SHOW, order))
        }
    }

    @Test
    fun `race produces a valid permutation of the six horses`() {
        val rng = Random(2026)
        repeat(500) {
            val pick = rng.nextInt(1, 7)
            val bet = HorseRacing.Bet.entries.random(rng)
            val result = racing.race(pick, bet, rng)
            assertEquals(6, result.finishingOrder.size, "finishingOrder must include every horse")
            assertEquals((1..6).toSet(), result.finishingOrder.toSet(), "duplicates or gaps")
            assertEquals(pick, result.pickedHorse)
            assertEquals(bet, result.bet)
            // multiplier consistency: nonzero iff the picked horse satisfies the bet.
            val expectedWin = HorseRacing.wins(pick, bet, result.finishingOrder)
            assertEquals(expectedWin, result.isWin, "isWin must match wins() predicate")
            if (expectedWin) {
                assertEquals(HorseRacing.horse(pick).multiplier(bet), result.multiplier, 1e-9)
            } else {
                assertEquals(0.0, result.multiplier, 1e-9)
            }
        }
    }

    @Test
    fun `picked horse out of range throws (1-indexed contract)`() {
        val rng = Random(0)
        listOf(0, -1, 7, 100).forEach { bad ->
            val ex = runCatching { racing.race(bad, HorseRacing.Bet.WIN, rng) }.exceptionOrNull()
            assertNotNull(ex, "picked $bad should throw")
            assertTrue(ex is IllegalArgumentException, "wrong exception type: $ex")
        }
    }

    @Test
    fun `win-probability simulation matches stated winProb within tolerance`() {
        // Sample lots of races and verify each horse wins (finishes 1st)
        // at the rate baked into its HorseProfile. 100k samples gives a
        // ~0.005 stdev on a 0.07 probability, so 0.02 is a comfortable
        // tolerance.
        val rng = Random(2026)
        val wins = IntArray(6)
        val n = 100_000
        repeat(n) {
            val result = racing.race(1, HorseRacing.Bet.WIN, rng)
            wins[result.finishingOrder[0] - 1]++
        }
        HorseRacing.HORSES.forEach { profile ->
            val observed = wins[profile.index - 1].toDouble() / n
            assertTrue(
                abs(observed - profile.winProb) < 0.02,
                "H${profile.index} winProb expected ~${profile.winProb}, observed $observed"
            )
        }
    }

    @Test
    fun `each WIN bet pays back about 0_92 RTP per horse (calibration)`() {
        // For each horse, repeatedly bet WIN on it and check empirical
        // RTP lands inside a generous band that brackets the 0.92
        // target with finite-sample slack on the longshot.
        val rng = Random(2026)
        val n = 50_000
        val stake = 100L
        HorseRacing.HORSES.forEach { profile ->
            var wagered = 0L
            var returned = 0L
            repeat(n) {
                val result = racing.race(profile.index, HorseRacing.Bet.WIN, rng)
                wagered += stake
                returned += (result.multiplier * stake).toLong()
            }
            val rtp = returned.toDouble() / wagered
            assertTrue(
                rtp in 0.85..1.05,
                "WIN RTP on H${profile.index} was $rtp (expected ~0.92)"
            )
        }
    }

    @Test
    fun `each PLACE bet pays back about 0_92 RTP per horse (joint distribution)`() {
        val rng = Random(2026)
        val n = 50_000
        val stake = 100L
        HorseRacing.HORSES.forEach { profile ->
            var wagered = 0L
            var returned = 0L
            repeat(n) {
                val result = racing.race(profile.index, HorseRacing.Bet.PLACE, rng)
                wagered += stake
                returned += (result.multiplier * stake).toLong()
            }
            val rtp = returned.toDouble() / wagered
            assertTrue(
                rtp in 0.85..1.05,
                "PLACE RTP on H${profile.index} was $rtp (expected ~0.92)"
            )
        }
    }

    @Test
    fun `each SHOW bet pays back about 0_92 RTP per horse (joint distribution)`() {
        val rng = Random(2026)
        val n = 50_000
        val stake = 100L
        HorseRacing.HORSES.forEach { profile ->
            var wagered = 0L
            var returned = 0L
            repeat(n) {
                val result = racing.race(profile.index, HorseRacing.Bet.SHOW, rng)
                wagered += stake
                returned += (result.multiplier * stake).toLong()
            }
            val rtp = returned.toDouble() / wagered
            assertTrue(
                rtp in 0.85..1.05,
                "SHOW RTP on H${profile.index} was $rtp (expected ~0.92)"
            )
        }
    }

    @Test
    fun `race is deterministic for a given seed`() {
        val seed = 12345L
        val a = racing.race(3, HorseRacing.Bet.PLACE, Random(seed))
        val b = racing.race(3, HorseRacing.Bet.PLACE, Random(seed))
        assertEquals(a.finishingOrder, b.finishingOrder)
        assertEquals(a.multiplier, b.multiplier)
    }

    @Test
    fun `every bet display label is non-empty and the multiplier helper agrees with the profile fields`() {
        HorseRacing.Bet.entries.forEach {
            assertNotNull(it.display)
            assertTrue(it.display.isNotBlank())
        }
        HorseRacing.HORSES.forEach { h ->
            assertEquals(h.winMult, h.multiplier(HorseRacing.Bet.WIN), 1e-9)
            assertEquals(h.placeMult, h.multiplier(HorseRacing.Bet.PLACE), 1e-9)
            assertEquals(h.showMult, h.multiplier(HorseRacing.Bet.SHOW), 1e-9)
        }
    }

    @Test
    fun `payout ordering — favourites pay less than longshots across every bet type`() {
        // H1 favourite must pay strictly less than H6 longshot on every
        // bet type; the whole point of the field is to widen the payout
        // tail.
        val h1 = HorseRacing.horse(1)
        val h6 = HorseRacing.horse(6)
        HorseRacing.Bet.entries.forEach { bet ->
            assertTrue(
                h1.multiplier(bet) < h6.multiplier(bet),
                "H1 should pay less than H6 on $bet (h1=${h1.multiplier(bet)}, h6=${h6.multiplier(bet)})"
            )
        }
    }

    @Test
    fun `losing bets never produce a positive multiplier`() {
        val rng = Random(2026)
        repeat(2_000) {
            val pick = rng.nextInt(1, 7)
            val bet = HorseRacing.Bet.entries.random(rng)
            val result = racing.race(pick, bet, rng)
            if (!HorseRacing.wins(pick, bet, result.finishingOrder)) {
                assertFalse(result.isWin)
                assertEquals(0.0, result.multiplier, 1e-9)
            }
        }
    }
}
