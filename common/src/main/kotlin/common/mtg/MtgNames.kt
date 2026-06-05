package common.mtg

/**
 * Card-name matching helpers that cope with multi-faced cards.
 *
 * Scryfall returns a double-faced / split card under its **full** name —
 * e.g. `Huntmaster of the Fells // Ravager of the Fells` — but a user
 * pasting a decklist usually writes just the front face
 * (`Huntmaster of the Fells`). [matchKeys] yields every name a card can be
 * looked up by (full name plus each face), all lower-cased, so resolving a
 * pasted list against fetched cards matches whichever form the user wrote.
 */
object MtgNames {

    private const val FACE_SEPARATOR = "//"

    /**
     * Lower-cased lookup keys for [fullName]: the whole name, plus each face
     * of a multi-faced name. Order is full-name-first; duplicates removed.
     */
    fun matchKeys(fullName: String): List<String> {
        val full = fullName.trim()
        val keys = LinkedHashSet<String>()
        if (full.isNotEmpty()) keys.add(full.lowercase())
        if (full.contains(FACE_SEPARATOR)) {
            full.split(FACE_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { keys.add(it.lowercase()) }
        }
        return keys.toList()
    }

    /** The lookup key for a user-entered name (trimmed, lower-cased). */
    fun lookupKey(name: String): String = name.trim().lowercase()
}
