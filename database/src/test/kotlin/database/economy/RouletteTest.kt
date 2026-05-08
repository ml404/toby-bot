package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RouletteTest {

    private val roulette = Roulette()

    @Test
    fun `wheel has 37 pockets numbered 0 through 36 with no duplicates`() {
        assertEquals(37, Roulette.POCKET_COUNT)
        assertEquals(37, Roulette.WHEEL_ORDER.size)
        assertEquals((0..36).toSet(), Roulette.WHEEL_ORDER.toSet())
    }

    @Test
    fun `zero is green, the 18 standard reds are red, the rest are black`() {
        assertEquals(Roulette.Color.GREEN, Roulette.colorOf(0))
        assertEquals(18, Roulette.RED_NUMBERS.size)
        Roulette.RED_NUMBERS.forEach { n ->
            assertEquals(Roulette.Color.RED, Roulette.colorOf(n), "$n should be red")
        }
        (1..36).filterNot { it in Roulette.RED_NUMBERS }.forEach { n ->
            assertEquals(Roulette.Color.BLACK, Roulette.colorOf(n), "$n should be black")
        }
    }

    @Test
    fun `even-money bets cover exactly 18 pockets (zero loses every outside bet)`() {
        val pockets = (0..36).toList()
        listOf(
            Roulette.Bet.RED, Roulette.Bet.BLACK,
            Roulette.Bet.ODD, Roulette.Bet.EVEN,
            Roulette.Bet.LOW, Roulette.Bet.HIGH,
        ).forEach { bet ->
            val winning = pockets.count { Roulette.wins(bet, null, it) }
            assertEquals(18, winning, "$bet should win on exactly 18 pockets")
            assertFalse(Roulette.wins(bet, null, 0), "$bet must lose on the green zero")
        }
    }

    @Test
    fun `dozen and column bets cover exactly 12 pockets (zero loses every outside bet)`() {
        val pockets = (0..36).toList()
        listOf(
            Roulette.Bet.DOZEN_1, Roulette.Bet.DOZEN_2, Roulette.Bet.DOZEN_3,
            Roulette.Bet.COLUMN_1, Roulette.Bet.COLUMN_2, Roulette.Bet.COLUMN_3,
        ).forEach { bet ->
            val winning = pockets.count { Roulette.wins(bet, null, it) }
            assertEquals(12, winning, "$bet should win on exactly 12 pockets")
            assertFalse(Roulette.wins(bet, null, 0), "$bet must lose on the green zero")
        }
    }

    @Test
    fun `dozens partition 1 through 36 with no overlap and no gaps`() {
        (1..36).forEach { n ->
            val hits = listOf(
                Roulette.Bet.DOZEN_1,
                Roulette.Bet.DOZEN_2,
                Roulette.Bet.DOZEN_3,
            ).count { Roulette.wins(it, null, n) }
            assertEquals(1, hits, "$n must belong to exactly one dozen")
        }
    }

    @Test
    fun `columns partition 1 through 36 with no overlap and no gaps`() {
        (1..36).forEach { n ->
            val hits = listOf(
                Roulette.Bet.COLUMN_1,
                Roulette.Bet.COLUMN_2,
                Roulette.Bet.COLUMN_3,
            ).count { Roulette.wins(it, null, n) }
            assertEquals(1, hits, "$n must belong to exactly one column")
        }
    }

    @Test
    fun `straight bet wins only on the matching pocket`() {
        (0..36).forEach { picked ->
            (0..36).forEach { landed ->
                val won = Roulette.wins(Roulette.Bet.STRAIGHT, picked, landed)
                assertEquals(picked == landed, won, "STRAIGHT($picked) on $landed")
            }
        }
    }

    @Test
    fun `straight bet without a number always loses`() {
        (0..36).forEach { landed ->
            assertFalse(
                Roulette.wins(Roulette.Bet.STRAIGHT, null, landed),
                "STRAIGHT with null number must always lose"
            )
        }
    }

    @Test
    fun `payout multipliers are odds + 1 so applyMultiplier nets the right credits`() {
        // applyMultiplier computes net = (multiplier × stake) - stake.
        // 1:1 → 2× → net = stake; 2:1 → 3× → net = 2×stake; 35:1 → 36× → net = 35×stake.
        listOf(
            Roulette.Bet.RED, Roulette.Bet.BLACK,
            Roulette.Bet.ODD, Roulette.Bet.EVEN,
            Roulette.Bet.LOW, Roulette.Bet.HIGH,
        ).forEach { assertEquals(2L, it.multiplier, "$it should pay 1:1 (multiplier 2×)") }

        listOf(
            Roulette.Bet.DOZEN_1, Roulette.Bet.DOZEN_2, Roulette.Bet.DOZEN_3,
            Roulette.Bet.COLUMN_1, Roulette.Bet.COLUMN_2, Roulette.Bet.COLUMN_3,
        ).forEach { assertEquals(3L, it.multiplier, "$it should pay 2:1 (multiplier 3×)") }

        assertEquals(36L, Roulette.Bet.STRAIGHT.multiplier, "STRAIGHT should pay 35:1 (multiplier 36×)")
    }

    @Test
    fun `spin returns a pocket within range and a multiplier consistent with the bet predicate`() {
        val rng = Random(2026)
        repeat(1_000) {
            val bet = Roulette.Bet.entries.random(rng)
            val number = if (bet == Roulette.Bet.STRAIGHT) rng.nextInt(0, 37) else null
            val spin = roulette.spin(bet, number, rng)
            assertTrue(spin.landed in 0..36, "pocket out of range: ${spin.landed}")
            assertEquals(bet, spin.bet)
            assertEquals(number, spin.straightNumber)
            assertEquals(Roulette.colorOf(spin.landed), spin.color)

            val expectedWin = Roulette.wins(bet, number, spin.landed)
            assertEquals(expectedWin, spin.isWin, "isWin must agree with wins() predicate")
            assertEquals(if (expectedWin) bet.multiplier else 0L, spin.multiplier)
        }
    }

    @Test
    fun `RTP across every bet type lands at the European 36 over 37 within tolerance`() {
        // Across n spins per bet type with a fixed stake, the total
        // returned should converge to (36/37) × totalWagered. Bigger n
        // for the high-variance straight bet keeps the band tight.
        val rng = Random(2026)
        val stake = 1_000L
        val targets = mapOf(
            Roulette.Bet.RED to 200_000,
            Roulette.Bet.DOZEN_1 to 200_000,
            Roulette.Bet.STRAIGHT to 500_000,
        )
        targets.forEach { (bet, n) ->
            var wagered = 0L
            var returned = 0L
            repeat(n) {
                val pick = if (bet == Roulette.Bet.STRAIGHT) rng.nextInt(0, 37) else null
                val spin = roulette.spin(bet, pick, rng)
                wagered += stake
                returned += spin.multiplier * stake
            }
            val rtp = returned.toDouble() / wagered.toDouble()
            assertTrue(rtp in 0.94..1.00, "RTP for $bet expected ~0.973 but saw $rtp")
        }
    }

    @Test
    fun `requiresNumber flag identifies the only bet type that needs an explicit pick`() {
        Roulette.Bet.entries.forEach { bet ->
            assertEquals(bet == Roulette.Bet.STRAIGHT, bet.requiresNumber, "$bet.requiresNumber")
        }
    }

    @Test
    fun `every bet display label is non-empty`() {
        Roulette.Bet.entries.forEach {
            assertNotNull(it.display)
            assertTrue(it.display.isNotBlank())
        }
    }
}
