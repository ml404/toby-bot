package common.mtg

/**
 * Writes a dealt set of packs as plain text — the format [PackListParser]
 * reads back. Both exports go through here in spirit: the bot's `/cube
 * generate` attachment calls it directly, and the website's "Download pack
 * list" button mirrors it in `magic.js` (`packsToText`), so a file saved from
 * either end loads on either end.
 *
 * ```
 * == Pack 1 (15 cards) ==
 *   Lightning Bolt
 *   Sol Ring
 * ```
 *
 * A card line may carry an annotation — the bot appends a price and image
 * URL. Annotations must be introduced by ` (` or ` — `, the two markers
 * [CardListParser] knows to strip, or they become part of the card's name
 * when the file is read back.
 */
object PackListWriter {

    /** Where an annotation starts. Anything after this is stripped on the way back in. */
    const val ANNOTATION_SEPARATOR = " — "

    /** The two-space indent that marks a card line under its header. */
    const val CARD_INDENT = "  "

    /**
     * The header opening pack [index] (1-based) of [size] cards. [suffix] is
     * an optional annotation (the bot hangs the pack's total value there).
     */
    fun header(index: Int, size: Int, suffix: String = ""): String {
        val marker = CardListParser.DIVIDER_MARKER
        return "$marker Pack $index ($size ${if (size == 1) "card" else "cards"})$suffix $marker"
    }

    /** One card line: the indent, the name, then any annotation as-is. */
    fun cardLine(name: String, annotation: String = ""): String = "$CARD_INDENT$name$annotation"

    /**
     * A whole deal. [annotate] decorates a card line (empty by default);
     * whatever it returns is appended after the name, and [packSuffix] does
     * the same for a pack's header.
     */
    fun write(
        packs: List<List<CubeCard>>,
        packSuffix: (List<CubeCard>) -> String = { "" },
        annotate: (CubeCard) -> String = { "" },
    ): String = buildString {
        packs.forEachIndexed { i, pack ->
            appendLine(header(i + 1, pack.size, packSuffix(pack)))
            pack.forEach { card -> appendLine(cardLine(card.name, annotate(card))) }
            appendLine()
        }
    }
}
