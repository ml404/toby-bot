package common.economy

/**
 * The catalogue of tradeable coins in the fake market. Every guild runs an
 * independent market for each coin listed here, so traders can pick a risk
 * appetite instead of being stuck with a single asset.
 *
 * [TOBY] is the baseline / "house" currency: the rest of the bot (titles,
 * casino top-ups, the wallet leaderboard, monthly snapshots) settles in
 * TOBY, and its balance lives in the legacy `user.toby_coins` column so all
 * of that keeps working untouched. The other coins are pure trading
 * instruments — their balances live in `user_coin_holding` — and exist
 * only to give the market more swing and more reasons to check the chart.
 *
 * The dial that makes a coin calm or wild is [volatility] (the annualised
 * sigma fed into the GBM random walk). [tradeImpact] is how hard a single
 * order shoves the price — thinner / wilder coins move more per trade. All
 * coins share the same gentle positive drift bias (see
 * [TobyCoinEngine.DRIFT_BIAS]) so none of them is a guaranteed loser; the
 * difference a trader feels is the size of the swings.
 */
enum class Coin(
    val displayName: String,
    val blurb: String,
    val riskLabel: String,
    val initialPrice: Double,
    /** Annualised volatility (sigma) for the GBM random walk. */
    val volatility: Double,
    /** Per-coin price impact of a single traded coin. */
    val tradeImpact: Double,
) {
    /**
     * Baseline blue-chip. Volatility and trade impact match the original
     * single-coin market exactly, so existing charts, balances, and the
     * rest of the economy carry over unchanged. ~±8 % on a bad day.
     */
    TOBY(
        displayName = "Toby Coin",
        blurb = "The server's flagship coin — the steady blue chip everything else settles in.",
        riskLabel = "Baseline",
        initialPrice = 100.0,
        volatility = 1.5,
        tradeImpact = 0.0001,
    ),

    /**
     * Low-volatility safe haven. Barely moves — somewhere to park credits
     * when the other coins are on fire. ~±2 % on a wild day.
     */
    TOBL(
        displayName = "Tobble",
        blurb = "A sleepy near-stablecoin — barely twitches, sleep easy.",
        riskLabel = "Low risk",
        initialPrice = 100.0,
        volatility = 0.4,
        tradeImpact = 0.00005,
    ),

    /**
     * Mid-cap mover. Noticeably swingier than TOBY without being unhinged
     * — the sweet spot for active traders. ~±17 % on a good run.
     */
    RUFF(
        displayName = "Rufftoken",
        blurb = "Mid-cap mover — bigger swings, bigger thrills than TOBY.",
        riskLabel = "Medium risk",
        initialPrice = 100.0,
        volatility = 3.0,
        tradeImpact = 0.00015,
    ),

    /**
     * Degen memecoin. Wild intraday swings, moons and craters by the hour.
     * Not for the faint-hearted — and exactly the kind of chaos that keeps
     * people glued to `/tobycoin chart`.
     */
    MOON(
        displayName = "Moonpup",
        blurb = "Pure degen fuel — moons and craters by the hour. Hold on tight.",
        riskLabel = "High risk",
        initialPrice = 100.0,
        volatility = 6.0,
        tradeImpact = 0.00025,
    ),
    ;

    /** Stored code / ticker. Same as the enum constant name. */
    val symbol: String get() = name

    companion object {
        /**
         * The coin every legacy code path and DB row defaults to. Keeping
         * this as TOBY is what lets the web/casino/titles layer and all the
         * existing single-coin data keep working with no changes.
         */
        val DEFAULT: Coin = TOBY

        /**
         * Resolve a stored coin code (case-insensitive). Unknown or null
         * values fall back to [DEFAULT] so a corrupted/legacy column can
         * never crash the price-tick loop or a trade.
         */
        fun fromSymbol(symbol: String?): Coin =
            symbol?.let { s -> entries.firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: DEFAULT
    }
}
