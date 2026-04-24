package database.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class TobyCoinEngineTest {

    @Test
    fun `tickRandomWalk is reproducible with the same seed`() {
        val a = TobyCoinEngine.tickRandomWalk(100.0, random = Random(42))
        val b = TobyCoinEngine.tickRandomWalk(100.0, random = Random(42))
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `tickRandomWalk moves the price away from the start value`() {
        val moved = (1..50).map { seed ->
            TobyCoinEngine.tickRandomWalk(100.0, random = Random(seed.toLong()))
        }
        assertTrue(moved.any { it != 100.0 }, "at least one tick should shift the price")
    }

    @Test
    fun `tickRandomWalk never returns below the price floor`() {
        var price = 1.5
        repeat(200) { price = TobyCoinEngine.tickRandomWalk(price, random = Random(it.toLong())) }
        assertTrue(price >= TobyCoinEngine.PRICE_FLOOR)
    }

    @Test
    fun `applyBuyPressure raises price and applySellPressure lowers it`() {
        val base = 100.0
        val up = TobyCoinEngine.applyBuyPressure(base, 10)
        val down = TobyCoinEngine.applySellPressure(base, 10)
        assertTrue(up > base, "buy should push price up")
        assertTrue(down < base, "sell should push price down")
    }

    @Test
    fun `applySellPressure respects the price floor for extreme trades`() {
        val result = TobyCoinEngine.applySellPressure(2.0, 1_000_000L)
        assertEquals(TobyCoinEngine.PRICE_FLOOR, result, 1e-9)
    }

    /**
     * Regression guard: with DRIFT=0 the lognormal Itô correction made the
     * median of many ticks decay, so users saw charts that "only went down".
     * Drift is now set so the median step is zero — a Monte-Carlo run should
     * land the median trajectory very close to where it started.
     */
    @Test
    fun `median of many ticks stays close to the starting price`() {
        val runs = 5_000
        val ticksPerRun = 200
        val startPrice = 100.0
        val rng = Random(20260424L)

        val finals = DoubleArray(runs) {
            var price = startPrice
            repeat(ticksPerRun) { price = TobyCoinEngine.tickRandomWalk(price, random = rng) }
            price
        }.also { it.sort() }

        val median = finals[finals.size / 2]
        assertTrue(
            median in 85.0..115.0,
            "median final price after $ticksPerRun ticks should stay near $startPrice but was $median"
        )
    }
}
