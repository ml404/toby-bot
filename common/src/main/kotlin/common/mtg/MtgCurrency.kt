package common.mtg

/**
 * The market currencies Scryfall prices cards in. [code] matches the key in
 * Scryfall's `prices` object (and the `CubeCard` price field it maps to);
 * [symbol] / [suffix] format an amount (`$1.50`, `€1.50`, `1.50 tix`).
 * Shared so the bot's "cube value" field, the web toggle, and the per-guild
 * `CUBE_CURRENCY` config all agree on the same set and spelling.
 */
enum class MtgCurrency(val code: String, val display: String, val symbol: String, val suffix: String) {
    USD("usd", "USD", "$", ""),
    EUR("eur", "EUR", "€", ""),
    TIX("tix", "Tix", "", " tix");

    /**
     * Wraps an already-formatted amount string in this currency's symbol and
     * suffix (`1.50` → `$1.50` / `€1.50` / `1.50 tix`). Use this for a raw
     * Scryfall price string (already two decimals) so the symbol/suffix
     * knowledge lives only here; use [format] for a computed [Double].
     */
    fun wrap(rawAmount: String): String = "$symbol$rawAmount$suffix"

    /** Formats a [Double] amount to two decimals in this currency: `$1.50` / `€1.50` / `1.50 tix`. */
    fun format(amount: Double): String = wrap("%.2f".format(amount))

    companion object {
        /** The currency used when a guild hasn't picked one. */
        val DEFAULT: MtgCurrency = USD

        /** Resolves a stored config value (a [code] or [display]) to a currency, or null. */
        fun fromCode(value: String?): MtgCurrency? {
            val v = value?.trim() ?: return null
            return entries.firstOrNull { it.code.equals(v, ignoreCase = true) || it.display.equals(v, ignoreCase = true) }
        }
    }
}
