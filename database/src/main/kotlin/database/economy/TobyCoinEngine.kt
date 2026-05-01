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

    /**
     * Annualised volatility. 1.5 ≈ memecoin / small-cap territory — gives
     * roughly ±0.5 % per 5-min tick and ±8 % per day, dramatic enough that
     * the chart visibly moves but recoverable enough that holders aren't
     * wiped out by a bad afternoon.
     */
    const val VOLATILITY = 1.5

    /**
     * Slight positive drift on top of the lognormal Itô correction, so the
     * median path trends upward instead of being flat. With pure
     * `0.5 * σ²` the median is technically flat but human eyes read the
     * lognormal asymmetry as "always sinking"; a small bias counters that.
     */
    const val DRIFT = 0.5 * VOLATILITY * VOLATILITY + 0.05

    /**
     * Per-coin trade impact on price. 1000 coins moves the market ~10 %.
     * Whales can still shift the chart but a single big sell no longer nukes
     * the market for everyone else.
     */
    const val TRADE_IMPACT = 0.0001

    /**
     * Default flat fee charged on each trade leg when the per-guild
     * `TRADE_BUY_FEE_PCT` / `TRADE_SELL_FEE_PCT` config entries are
     * unset. The fee is skimmed off the trader's payment (or proceeds)
     * and routed into the per-guild jackpot pool, which casino
     * minigame wins can hit for a payout. 1 % per leg means a
     * buy-then-sell round trip pays ~2 % to the pool, which together
     * with midpoint pricing makes the round-trip exploit unprofitable
     * at any size and seeds gameplay.
     */
    const val TRADE_FEE = 0.01

    /**
     * Hard cap on the configurable per-guild trade fee. 25 % keeps
     * trading viable even with the most aggressive admin setting —
     * any higher and a buy-sell round trip costs over half the
     * principal, which would effectively disable the economy.
     */
    const val MAX_TRADE_FEE = 0.25

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

    /**
     * Net credits the seller actually receives for [coins] sold at
     * pre-pressure [price] — the same math `EconomyTradeService.sell`
     * applies, exposed so callers that need to size a sell to a
     * target shortfall (Titles' "Buy with TOBY", casino top-up) don't
     * re-derive it.
     *
     * = floor(midpoint × N) − floor(floor(midpoint × N) × TRADE_FEE)
     */
    fun proceedsForSell(price: Double, coins: Long, feeRate: Double = TRADE_FEE): Long {
        if (coins <= 0L || price <= 0.0) return 0L
        val newPrice = applySellPressure(price, coins)
        val midpoint = (price + newPrice) / 2.0
        val gross = kotlin.math.floor(midpoint * coins).toLong()
        val fee = kotlin.math.floor(gross * feeRate).toLong()
        return gross - fee
    }

    /**
     * Smallest coin count whose sell proceeds (after slippage + fee)
     * cover [shortfall]. The naive `ceil(shortfall / price)` undercounts
     * by 1–2 coins because the trader eats half the slippage and 1 %
     * goes to the jackpot pool. We bump iteratively; bounded for
     * safety against pathological `k × N` regimes.
     *
     * Returns the *true* required coin count regardless of what the
     * user actually holds — callers compare against the balance and
     * surface "insufficient coins" themselves.
     */
    fun coinsNeededForShortfall(shortfall: Long, price: Double, feeRate: Double = TRADE_FEE): Long {
        if (shortfall <= 0L || price <= 0.0) return 0L
        var n = kotlin.math.ceil(shortfall.toDouble() / price).toLong().coerceAtLeast(1L)
        // 16 iterations: empirically the loop converges in 1–2 for sane
        // shortfalls. The cap is just paranoia for adversarial inputs.
        repeat(16) {
            if (proceedsForSell(price, n, feeRate) >= shortfall) return n
            n += 1L
        }
        return n
    }

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
