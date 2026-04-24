package database.economy

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure-math pricing logic for the Toby Coin market. No Spring, no I/O — so
 * it can be unit-tested with a seeded Random.
 *
 * The market uses a Geometric Brownian Motion random walk (same family of
 * model as Black-Scholes, so the chart has the jagged look real stock
 * charts do) plus a trade-pressure nudge when users buy/sell.
 */
object TobyCoinEngine {

    /** Credits-per-coin a brand-new guild market starts at. */
    const val INITIAL_PRICE = 100.0

    /** Minimum allowed price — prevents floor-hugging or negative values. */
    const val PRICE_FLOOR = 1.0

    /** Scheduled tick cadence — also used as Δt in the GBM formula. */
    const val TICK_INTERVAL_SECONDS = 5L * 60L

    /** Annualised volatility. 0.6 ≈ typical small-cap / crypto territory. */
    const val VOLATILITY = 0.6

    // Drift chosen so E[log(S_{t+dt}/S_t)] = 0 — the median path is flat and
    // the walk is equally likely to go up or down each tick. With DRIFT=0 the
    // lognormal Itô correction makes the median decay, so most observed paths
    // trend downward even though the mean is conserved.
    const val DRIFT = 0.5 * VOLATILITY * VOLATILITY

    /** Per-coin trade impact on price. 1000 coins moves the market ~40 %. */
    const val TRADE_IMPACT = 0.0004

    private const val SECONDS_PER_YEAR = 365.0 * 24.0 * 60.0 * 60.0

    /**
     * Apply one GBM tick of length [dtSeconds] (default = one scheduled tick).
     * Uses [random] for reproducibility in tests.
     */
    fun tickRandomWalk(
        price: Double,
        dtSeconds: Long = TICK_INTERVAL_SECONDS,
        random: Random = Random.Default
    ): Double {
        val dt = dtSeconds.toDouble() / SECONDS_PER_YEAR
        val z = gaussian(random)
        val factor = exp((DRIFT - 0.5 * VOLATILITY * VOLATILITY) * dt + VOLATILITY * sqrt(dt) * z)
        return max(PRICE_FLOOR, price * factor)
    }

    fun applyBuyPressure(price: Double, coins: Long): Double =
        max(PRICE_FLOOR, price * (1.0 + TRADE_IMPACT * coins))

    fun applySellPressure(price: Double, coins: Long): Double =
        max(PRICE_FLOOR, price * (1.0 - TRADE_IMPACT * coins))

    /** Box–Muller — Kotlin's stdlib has nextDouble() but no normal sampler. */
    private fun gaussian(random: Random): Double {
        var u1: Double
        do {
            u1 = random.nextDouble()
        } while (u1 <= 0.0)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
