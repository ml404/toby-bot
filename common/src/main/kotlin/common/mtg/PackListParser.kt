package common.mtg

/**
 * Reads a downloaded pack list back into its packs — the read side of the
 * Generate tab's "Download pack list (.txt)", which writes:
 *
 * ```
 * == Pack 1 (15 cards) ==
 *   Lightning Bolt
 *   Sol Ring
 *
 * == Pack 2 (15 cards) ==
 *   ...
 * ```
 *
 * A `==` divider line opens a new pack; everything between dividers is
 * parsed by [CardListParser], so quantities, set/collector tags and comments
 * are handled exactly as they are anywhere else a list is pasted. Text with
 * no dividers at all is treated as a single pack, so a hand-written or
 * hand-trimmed list still loads.
 *
 * Order matters here in a way it doesn't for a cube list: a pack is the deal
 * that actually happened, so entries stay in file order and duplicates are
 * kept as separate copies rather than collapsed.
 */
object PackListParser {

    /** One recovered pack: its header text (null when there was no divider) and its cards. */
    data class Pack(val label: String?, val entries: List<CardListParser.Entry>)

    fun parse(text: String): List<Pack> {
        val packs = mutableListOf<Pack>()
        var label: String? = null
        var block = StringBuilder()

        fun flush() {
            val entries = CardListParser.parse(block.toString())
            if (entries.isNotEmpty()) packs.add(Pack(label, entries))
            block = StringBuilder()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(CardListParser.DIVIDER_MARKER)) {
                flush()
                label = line.trim('=', ' ').ifEmpty { null }
            } else {
                block.append(raw).append('\n')
            }
        }
        flush()
        return packs
    }
}
