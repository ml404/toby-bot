package common.mtg

/**
 * The "as-fan" buckets the doc cares about: one per colour, plus
 * multicolour, colourless, and land. These are the characteristics a
 * cube designer balances a pack around so every colour drafts and every
 * archetype has enough fixing.
 */
enum class CardCategory(val displayName: String) {
    WHITE("White"),
    BLUE("Blue"),
    BLACK("Black"),
    RED("Red"),
    GREEN("Green"),
    MULTICOLOR("Multicolor"),
    COLORLESS("Colorless"),
    LAND("Land");

    companion object {
        private val BY_COLOR: Map<MtgColor, CardCategory> = mapOf(
            MtgColor.WHITE to WHITE,
            MtgColor.BLUE to BLUE,
            MtgColor.BLACK to BLACK,
            MtgColor.RED to RED,
            MtgColor.GREEN to GREEN,
        )

        /** The single-colour bucket for a mono-coloured card. */
        fun ofColor(color: MtgColor): CardCategory = BY_COLOR.getValue(color)
    }
}

/**
 * A card in a cube pool. Deliberately minimal — only what the cube tools
 * need to randomise packs and compute as-fan. Built from Scryfall data
 * (see the bot's `ScryfallCubeFetcher` / web's `CubeWebService`) but has
 * no dependency on any HTTP client, so it stays a `common` value type.
 *
 * Land classification keys off [isLand] rather than [colors] because a
 * land is bucketed as a land regardless of any colour identity it has
 * (e.g. a dual land that taps for two colours is still "a land" for
 * as-fan purposes — it's fixing, not a spell of those colours).
 */
data class CubeCard(
    val name: String,
    val colors: Set<MtgColor> = emptySet(),
    val isLand: Boolean = false,
    val typeLine: String = "",
    val manaValue: Double = 0.0,
    /**
     * Optional link to the card's image (Scryfall). Presentation-only — the
     * `common` maths ignore it — but kept on the card so a surface that has
     * it (the bot's pack list) can show it without a second lookup.
     */
    val imageUrl: String? = null,
    /**
     * Raw Scryfall `rarity` string (`common`/`uncommon`/`rare`/`mythic`/…),
     * or null when unknown. Kept raw — [common.mtg.CubeAnalytics] resolves it
     * to a [Rarity] — so this stays a dumb value type, like [imageUrl].
     */
    val rarity: String? = null,
    /**
     * Raw Scryfall `mana_cost` string (e.g. `{1}{W}{W}`), or null. Kept raw —
     * [common.mtg.CubeAnalytics] counts its colour pips for the pip-weighted
     * colour balance — so this stays a dumb value type.
     */
    val manaCost: String? = null,
    /**
     * The card's rules text (Scryfall `oracle_text`), or null. Presentation-
     * only — shown on a card lookup / `[[mention]]`, ignored by the maths.
     * For a double-faced card both faces are combined.
     */
    val oracleText: String? = null,
    /**
     * The back-face image for a double-faced card (Scryfall second face
     * `image_uris.normal`), or null for single-faced cards. Presentation-only.
     */
    val imageUrlBack: String? = null,
    /** Scryfall market prices (raw strings, e.g. "1.50"), or null when unpriced. Presentation-only. */
    val priceUsd: String? = null,
    val priceEur: String? = null,
    val priceTix: String? = null,
    /**
     * The play formats this card is currently legal in (Scryfall `legalities`
     * entries with status "legal"), display-cased, in [CubeCard.FORMATS] order.
     * Presentation-only.
     */
    val legalFormats: List<String> = emptyList(),
) {
    /** Which as-fan bucket this card falls into. Lands first, then by colour count. */
    val category: CardCategory
        get() = when {
            isLand -> CardCategory.LAND
            colors.isEmpty() -> CardCategory.COLORLESS
            colors.size >= 2 -> CardCategory.MULTICOLOR
            else -> CardCategory.ofColor(colors.first())
        }

    /** This card's raw price string in [currency] (e.g. "1.50"), or null when unpriced. */
    fun price(currency: MtgCurrency): String? = when (currency) {
        MtgCurrency.USD -> priceUsd
        MtgCurrency.EUR -> priceEur
        MtgCurrency.TIX -> priceTix
    }

    companion object {
        /**
         * Play formats surfaced on a card panel, as `scryfall key to Display`,
         * in the order shown. Both parsers map Scryfall `legalities` through
         * this so the bot and web list the same formats consistently.
         */
        val FORMATS: List<Pair<String, String>> = listOf(
            "standard" to "Standard",
            "pioneer" to "Pioneer",
            "modern" to "Modern",
            "legacy" to "Legacy",
            "vintage" to "Vintage",
            "pauper" to "Pauper",
            "commander" to "Commander",
        )

        /** The display-cased formats this card is legal in, from a Scryfall `legalities` map (key→status). */
        fun legalFormatsOf(statusByFormat: (String) -> String?): List<String> =
            FORMATS.filter { (key, _) -> statusByFormat(key) == "legal" }.map { it.second }

        /**
         * Whether a Scryfall `type_line` denotes a land, judged by the
         * **front face** only. A modal/transform card's combined type line
         * (e.g. "Legendary Enchantment // Legendary Land" for
         * Legion's Landing) mentions "Land" on the back, but the card is
         * drafted and cast as its front face — so it shouldn't be bucketed
         * as a land. Only when the front face itself is a land (e.g.
         * "Land // Creature" for Westvale Abbey) is it a land.
         */
        fun isLandType(typeLine: String): Boolean =
            typeLine.substringBefore("//").contains("Land", ignoreCase = true)

        /**
         * Whether a `type_line`'s **front face** is a basic land — the five
         * basics, their Snow-Covered variants, and Wastes all carry "Basic"
         * in their type ("Basic Land — Forest", "Basic Snow Land — Island",
         * "Basic Land"). Used to allow duplicate basics in a singleton cube
         * without a brittle name list.
         */
        fun isBasicType(typeLine: String): Boolean =
            typeLine.substringBefore("//").contains("Basic", ignoreCase = true)
    }
}
