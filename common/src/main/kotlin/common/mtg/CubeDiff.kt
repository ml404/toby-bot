package common.mtg

/**
 * Compares two card lists by name — the read side of iterating a cube ("what
 * changed between this version and the last?"). Pure name-level maths: no
 * Scryfall, so it's instant and works on any pasted list or saved cube.
 *
 * Names are matched case-insensitively (via [MtgNames.lookupKey]); the
 * display name keeps the spelling from whichever side has the card. Counts
 * are summed per name, so a list with `3 Forest` then `7 Forest` is ten.
 */
object CubeDiff {

    /** One card's change from list A to list B; [from]/[to] are its copy counts. */
    data class Line(val name: String, val from: Int, val to: Int)

    data class Diff(
        val added: List<Line>,    // in B, not A (from == 0)
        val removed: List<Line>,  // in A, not B (to == 0)
        val changed: List<Line>,  // in both, count differs
        val sizeA: Int,           // total cards in A
        val sizeB: Int,           // total cards in B
    )

    fun diff(a: List<CardListParser.Entry>, b: List<CardListParser.Entry>): Diff {
        val mapA = collapse(a)
        val mapB = collapse(b)
        val added = mutableListOf<Line>()
        val removed = mutableListOf<Line>()
        val changed = mutableListOf<Line>()
        for (key in (mapA.keys + mapB.keys)) {
            val from = mapA[key]?.second ?: 0
            val to = mapB[key]?.second ?: 0
            val name = mapB[key]?.first ?: mapA.getValue(key).first
            when {
                from == 0 -> added.add(Line(name, 0, to))
                to == 0 -> removed.add(Line(name, from, 0))
                from != to -> changed.add(Line(name, from, to))
            }
        }
        return Diff(
            added = added.sortedBy { it.name.lowercase() },
            removed = removed.sortedBy { it.name.lowercase() },
            changed = changed.sortedBy { it.name.lowercase() },
            sizeA = mapA.values.sumOf { it.second },
            sizeB = mapB.values.sumOf { it.second },
        )
    }

    /** Collapses entries to a `lookupKey -> (displayName, totalCount)` map. */
    private fun collapse(entries: List<CardListParser.Entry>): Map<String, Pair<String, Int>> {
        val out = LinkedHashMap<String, Pair<String, Int>>()
        for (entry in entries) {
            val key = MtgNames.lookupKey(entry.name)
            val existing = out[key]
            out[key] = (existing?.first ?: entry.name) to ((existing?.second ?: 0) + entry.count)
        }
        return out
    }
}
