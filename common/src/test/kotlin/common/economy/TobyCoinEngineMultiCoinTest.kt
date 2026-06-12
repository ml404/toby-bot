package common.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Per-coin behaviour of the shared GBM engine: the dial that makes a coin
 * feel calm or wild is [Coin.volatility], and trade pressure scales with
 * [Coin.tradeImpact]. These are Monte-Carlo with a fixed seed, so they're
 * deterministic (not flaky) — the seed just has to satisfy the inequality.
 */
internal class TobyCoinEngineMultiCoinTest {

    // 5th-to-95th percentile spread of the final price after a day of ticks.
    private fun dailySpread(coin: Coin, runs: Int = 4000, ticksPerDay: Int = 288): Double {
        val rng = Random(20260612L)
        val finals = DoubleArray(runs) {
            var price = 100.0
            repeat(ticksPerDay) { price = TobyCoinEngine.tickRandomWalk(price, coin, random = rng) }
            price
        }.also { it.sort() }
        val p5 = finals[(runs * 0.05).toInt()]
        val p95 = finals[(runs * 0.95).toInt()]
        return p95 - p5
    }

    @Test
    fun `wilder coins produce a wider daily spread`() {
        val stbl = dailySpread(Coin.TOBL)
        val toby = dailySpread(Coin.TOBY)
        val ruff = dailySpread(Coin.RUFF)
        val moon = dailySpread(Coin.MOON)
        assertTrue(stbl < toby, "TOBL spread $stbl should be < TOBY $toby")
        assertTrue(toby < ruff, "TOBY spread $toby should be < RUFF $ruff")
        assertTrue(ruff < moon, "RUFF spread $ruff should be < MOON $moon")
    }

    @Test
    fun `the default coin overload is exactly TOBY`() {
        val implicit = TobyCoinEngine.tickRandomWalk(100.0, random = Random(7))
        val explicit = TobyCoinEngine.tickRandomWalk(100.0, Coin.TOBY, random = Random(7))
        assertEquals(implicit, explicit, 1e-12)
    }

    @Test
    fun `buy pressure scales up with the coin's trade impact`() {
        val tobl = TobyCoinEngine.applyBuyPressure(100.0, 1_000L, Coin.TOBL)
        val toby = TobyCoinEngine.applyBuyPressure(100.0, 1_000L, Coin.TOBY)
        val moon = TobyCoinEngine.applyBuyPressure(100.0, 1_000L, Coin.MOON)
        assertTrue(tobl < toby, "TOBL ($tobl) moves less than TOBY ($toby)")
        assertTrue(toby < moon, "TOBY ($toby) moves less than MOON ($moon)")
    }

    @Test
    fun `sell pressure scales down with the coin's trade impact`() {
        val tobl = TobyCoinEngine.applySellPressure(100.0, 1_000L, Coin.TOBL)
        val toby = TobyCoinEngine.applySellPressure(100.0, 1_000L, Coin.TOBY)
        val moon = TobyCoinEngine.applySellPressure(100.0, 1_000L, Coin.MOON)
        assertTrue(moon < toby, "MOON ($moon) drops more than TOBY ($toby)")
        assertTrue(toby < tobl, "TOBY ($toby) drops more than TOBL ($tobl)")
    }

    @Test
    fun `every coin respects the price floor under a long crash`() {
        Coin.entries.forEach { coin ->
            var price = 1.2
            repeat(400) { price = TobyCoinEngine.tickRandomWalk(price, coin, random = Random(it.toLong())) }
            assertTrue(price >= TobyCoinEngine.PRICE_FLOOR, "${coin.symbol} sank below the floor: $price")
        }
    }

    @Test
    fun `each coin's walk is reproducible with the same seed`() {
        Coin.entries.forEach { coin ->
            val a = TobyCoinEngine.tickRandomWalk(100.0, coin, random = Random(99))
            val b = TobyCoinEngine.tickRandomWalk(100.0, coin, random = Random(99))
            assertEquals(a, b, 1e-12, "${coin.symbol} not reproducible")
        }
    }

    @Test
    fun `proceedsForSell and coinsNeededForShortfall honour per-coin impact`() {
        // A wilder coin slips harder on the way out, so the same sell yields
        // fewer credits and a shortfall needs more of it.
        val tobyProceeds = TobyCoinEngine.proceedsForSell(100.0, 500L, coin = Coin.TOBY)
        val moonProceeds = TobyCoinEngine.proceedsForSell(100.0, 500L, coin = Coin.MOON)
        assertTrue(moonProceeds < tobyProceeds, "MOON ($moonProceeds) should net less than TOBY ($tobyProceeds)")

        val tobyNeeded = TobyCoinEngine.coinsNeededForShortfall(5_000L, 100.0, coin = Coin.TOBY)
        val moonNeeded = TobyCoinEngine.coinsNeededForShortfall(5_000L, 100.0, coin = Coin.MOON)
        assertTrue(moonNeeded >= tobyNeeded, "MOON needs at least as many coins ($moonNeeded vs $tobyNeeded)")
    }
}
