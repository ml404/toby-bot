package common.mtg

/**
 * The five colours of Magic: The Gathering's colour pie. Pure data — no
 * Spring, no JDA — so the cube tooling ([AsFan], [PackGenerator],
 * [DraftEngine]) can live in `common` and be unit-tested in isolation.
 *
 * [symbol] is the single-letter mana code Scryfall (and the wider game)
 * uses in a card's `color_identity` / `colors` arrays — W, U, B, R, G.
 */
enum class MtgColor(val symbol: Char, val displayName: String) {
    WHITE('W', "White"),
    BLUE('U', "Blue"),
    BLACK('B', "Black"),
    RED('R', "Red"),
    GREEN('G', "Green");

    companion object {
        /** Maps a mana symbol (case-insensitive) to its colour, or null. */
        fun fromSymbol(symbol: Char): MtgColor? =
            entries.firstOrNull { it.symbol == symbol.uppercaseChar() }

        /**
         * Parses a Scryfall-style colour array (`["W", "U"]`) into a set,
         * silently dropping anything that isn't a recognised symbol so a
         * stray value in the API payload can't blow up cube generation.
         */
        fun parse(symbols: Iterable<String>): Set<MtgColor> =
            symbols.mapNotNull { it.trim().firstOrNull()?.let(::fromSymbol) }.toSet()
    }
}
