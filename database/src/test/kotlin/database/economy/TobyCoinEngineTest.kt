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
     * Drift is now slightly positive so the median trends gently up — a
     * Monte-Carlo run over a short window should land the median close to
     * the starting price (the upward bias only meaningfully accumulates
     * over much longer horizons).
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
            median in 80.0..120.0,
            "median final price after $ticksPerRun ticks should stay near $startPrice but was $median"
        )
    }

    /**
     * The market is meant to feel volatile enough to be fun. After a day's
     * worth of ticks (~288), the spread between the 5th and 95th percentiles
     * should be wide — a flat-line chart is boring.
     */
    @Test
    fun `daily ticks produce visibly volatile spread across paths`() {
        val runs = 5_000
        val ticksPerDay = 288   // 5-min ticks * 12 per hour * 24
        val startPrice = 100.0
        val rng = Random(20260424L)

        val finals = DoubleArray(runs) {
            var price = startPrice
            repeat(ticksPerDay) { price = TobyCoinEngine.tickRandomWalk(price, random = rng) }
            price
        }.also { it.sort() }

        val p5 = finals[(runs * 0.05).toInt()]
        val p95 = finals[(runs * 0.95).toInt()]
        // With σ=1.5 annualised, 1-day log-stdev ≈ 0.078; ~95% band should
        // span at least ±10% from the start price.
        assertTrue(p5 < 90.0,  "5th percentile after a day should be below 90 but was $p5")
        assertTrue(p95 > 110.0, "95th percentile after a day should be above 110 but was $p95")
    }

    @Test
    fun `trade impact is meaningful but not market-nuking`() {
        // 1000 coins should move the price by roughly 10%, not 40%.
        val priceAfterBuy  = TobyCoinEngine.applyBuyPressure(100.0, 1_000L)
        val priceAfterSell = TobyCoinEngine.applySellPressure(100.0, 1_000L)
        assertTrue(priceAfterBuy in 105.0..115.0,
            "1000 coins bought should move price ~10% up, was ${priceAfterBuy}")
        assertTrue(priceAfterSell in 85.0..95.0,
            "1000 coins sold should move price ~10% down, was ${priceAfterSell}")
    }
}
