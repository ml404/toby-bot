package common.mtg

/**
 * Parses a pasted decklist into (name, count) entries. Shared by the web
 * tool and the Discord command so they agree on the format:
 *
 *  - one card per line;
 *  - an optional leading quantity — `3 Forest`, `3x Forest`, `3X Forest`;
 *  - an optional trailing set/collector tag — `Lightning Bolt (2X2) 117`;
 *  - an optional trailing finish marker — `Sol Ring (C21) 263 *F*`;
 *  - an optional trailing ` — …` annotation, which is how [PackListWriter]
 *    hangs a price or an image URL off a card line;
 *  - blank lines, `#` / `//` comments, and exporter section headers
 *    (`Deck`, `Sideboard`, `Commander`, …) ignored;
 *  - `== ... ==` divider lines ignored, so a pack list downloaded from the
 *    Generate tab (`== Pack 1 (15 cards) ==`) pastes straight back in
 *    without its headers surfacing as "couldn't find" noise.
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

    // A trailing " — ..." annotation: what the bot's pack export hangs a
    // price and image URL off a card line with. Stripped BEFORE SET_SUFFIX,
    // because an unpriced card carries no bracket for SET_SUFFIX to catch
    // and would otherwise keep the whole URL as part of its name. Safe to
    // strip wholesale: card names use hyphens, never a spaced em dash.
    private val ANNOTATION_SUFFIX = Regex("\\s+—\\s.*$")

    // Standalone section headers deck exporters (Arena, MTGO, Moxfield) emit
    // between card groups. No real Magic card is named any of these, so a
    // line that is exactly one of them — and carries no quantity — is a
    // header, not a card.
    private val SECTION_HEADERS = setOf(
        "deck", "sideboard", "commander", "companion",
        "maybeboard", "mainboard", "tokens", "about",
    )

    /**
     * A `== Pack 1 (15 cards) ==` style divider — what the pack download on
     * the Generate tab writes between packs. No Magic card name starts with
     * `==`, so such a line is always a header; [PackListParser] splits on the
     * same marker to recover the pack boundaries.
     */
    const val DIVIDER_MARKER = "=="

    fun parse(text: String): List<Entry> =
        text.lineSequence().mapNotNull { raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@mapNotNull null
            if (line.lowercase() in SECTION_HEADERS || line.startsWith(DIVIDER_MARKER)) return@mapNotNull null
            var count = 1
            QUANTITY_PREFIX.find(line)?.let { match ->
                count = match.groupValues[1].toIntOrNull()?.coerceIn(1, MAX_PER_NAME) ?: 1
                line = line.substring(match.range.last + 1).trim()
            }
            line = line.replace(ANNOTATION_SUFFIX, "").replace(SET_SUFFIX, "").replace(FINISH_SUFFIX, "").trim()
            if (line.isEmpty()) null else Entry(line, count)
        }.toList()
}
