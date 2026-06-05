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
) {
    /** Which as-fan bucket this card falls into. Lands first, then by colour count. */
    val category: CardCategory
        get() = when {
            isLand -> CardCategory.LAND
            colors.isEmpty() -> CardCategory.COLORLESS
            colors.size >= 2 -> CardCategory.MULTICOLOR
            else -> CardCategory.ofColor(colors.first())
        }

    companion object {
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
    }
}
