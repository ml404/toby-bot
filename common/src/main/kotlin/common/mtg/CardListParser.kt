package common.mtg

/**
 * Parses a pasted decklist into (name, count) entries. Shared by the web
 * tool and the Discord command so they agree on the format:
 *
 *  - one card per line;
 *  - an optional leading quantity — `3 Forest`, `3x Forest`, `3X Forest`;
 *  - an optional trailing set/collector tag — `Lightning Bolt (2X2) 117`;
 *  - an optional trailing finish marker — `Sol Ring (C21) 263 *F*`;
 *  - blank lines, `#` / `//` comments, and exporter section headers
 *    (`Deck`, `Sideboard`, `Commander`, …) ignored.
 *
 * This keeps a list pasted straight out of Arena / MTGO / Moxfield from
 * surfacing its group headers as "couldn't find" noise.
 *
 * Counts are capped at [MAX_PER_NAME] so a stray `99999 Forest` can't blow
 * up the pool.
 */
object CardListParser {

    data class Entry(val name: String, val count: Int)

    const val MAX_PER_NAME = 100

    private val QUANTITY_PREFIX = Regex("^(\\d+)[xX]?\\s+")
    private val SET_SUFFIX = Regex("\\s+\\([^)]*\\).*$")

    // A trailing finish marker some exporters append (`*F*` foil, `*E*`
    // etched), kept separate so it's stripped even on lines without a
    // set/collector tag for SET_SUFFIX to swallow.
    private val FINISH_SUFFIX = Regex("\\s+\\*[^*]*\\*\\s*$")

    // Standalone section headers deck exporters (Arena, MTGO, Moxfield) emit
    // between card groups. No real Magic card is named any of these, so a
    // line that is exactly one of them — and carries no quantity — is a
    // header, not a card.
    private val SECTION_HEADERS = setOf(
        "deck", "sideboard", "commander", "companion",
        "maybeboard", "mainboard", "tokens", "about",
    )

    fun parse(text: String): List<Entry> =
        text.lineSequence().mapNotNull { raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@mapNotNull null
            if (line.lowercase() in SECTION_HEADERS) return@mapNotNull null
            var count = 1
            QUANTITY_PREFIX.find(line)?.let { match ->
                count = match.groupValues[1].toIntOrNull()?.coerceIn(1, MAX_PER_NAME) ?: 1
                line = line.substring(match.range.last + 1).trim()
            }
            line = line.replace(SET_SUFFIX, "").replace(FINISH_SUFFIX, "").trim()
            if (line.isEmpty()) null else Entry(line, count)
        }.toList()
}
