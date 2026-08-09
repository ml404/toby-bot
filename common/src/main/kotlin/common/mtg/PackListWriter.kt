package common.mtg

/**
 * Writes a dealt set of packs as plain text — the format [PackListParser]
 * reads back. Both exports go through here in spirit: the bot's `/cube
 * generate` attachment calls it directly, and the website's "Download pack
 * list" button mirrors it in `magic-format.js` (`packsToText`), so a file
 * saved from either end loads on either end.
 *
 * ```
 * # cube:vintage — 24 packs of 15 — 2026-08-09
 *
 * == Pack 1 (15 cards) ==
 *   Lightning Bolt
 *   Sol Ring
 * ```
 *
 * The leading `#` line says where the deal came from, so a folder of
 * `cube-packs.txt` files can be told apart. It's a comment, which
 * [CardListParser] already ignores, so it costs nothing on the way back in.
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
     * The `#` provenance line: where the deal came from, its shape, and when
     * it was dealt. Any part may be missing — [source] is null for a deal
     * whose origin we don't know (an imported file), [dealtOn] for a caller
     * with no clock. Returns null when there's nothing worth saying.
     */
    fun provenance(
        packCount: Int,
        packSize: Int,
        source: String? = null,
        dealtOn: String? = null,
    ): String? {
        val from = source?.trim()?.ifEmpty { null }
        val on = dealtOn?.trim()?.ifEmpty { null }
        // The shape is right there in the file. Without a source or a date
        // there's no provenance to record, so don't write a line at all.
        if (from == null && on == null) return null
        val shape = "$packCount ${if (packCount == 1) "pack" else "packs"} of $packSize"
        return "# " + listOfNotNull(from, shape, on).joinToString(ANNOTATION_SEPARATOR)
    }

    /**
     * A whole deal. [annotate] decorates a card line (empty by default);
     * whatever it returns is appended after the name, and [packSuffix] does
     * the same for a pack's header. [source] and [dealtOn] fill in the
     * leading `#` provenance line.
     */
    fun write(
        packs: List<List<CubeCard>>,
        packSuffix: (List<CubeCard>) -> String = { "" },
        annotate: (CubeCard) -> String = { "" },
        source: String? = null,
        dealtOn: String? = null,
    ): String = buildString {
        if (packs.isNotEmpty()) {
            provenance(packs.size, packs.maxOf { it.size }, source, dealtOn)?.let {
                appendLine(it)
                appendLine()
            }
        }
        packs.forEachIndexed { i, pack ->
            appendLine(header(i + 1, pack.size, packSuffix(pack)))
            pack.forEach { card -> appendLine(cardLine(card.name, annotate(card))) }
            appendLine()
        }
    }
}
