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

    /**
     * Indexes [items] by every [matchKeys] form of their name (full name plus
     * each face), so a pasted front-face name resolves to the full-name card
     * Scryfall returns. The first item wins a contested key — matching how
     * both the bot's `MtgPoolResolver` and the web's cube service tie a
     * resolved list back to its entries, kept here so they can't diverge.
     */
    fun <T> index(items: Iterable<T>, nameOf: (T) -> String): Map<String, T> {
        val byKey = HashMap<String, T>()
        items.forEach { item ->
            matchKeys(nameOf(item)).forEach { key -> byKey.putIfAbsent(key, item) }
        }
        return byKey
    }

    /**
     * The identifier to send to Scryfall's collection lookup for [name].
     * Scryfall matches a card by a single face, **not** by its full
     * `A // B` name, so a pasted full name (e.g.
     * `Archangel Avacyn // Avacyn, the Purifier`) must be reduced to its
     * front face or it comes back "not found". Single-faced and front-face
     * names pass through unchanged; the returned card (under its full name)
     * is tied back to the original entry by [matchKeys].
     */
    fun requestName(name: String): String {
        val trimmed = name.trim()
        val idx = trimmed.indexOf(FACE_SEPARATOR)
        return if (idx >= 0) trimmed.substring(0, idx).trim() else trimmed
    }
}
