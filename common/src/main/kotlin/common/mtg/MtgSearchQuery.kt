package common.mtg

/**
 * Builds a Scryfall search query from the structured card-search inputs shared
 * by the Discord `/mtgcard search` command and the web Magic toolkit's card
 * search. Keeping it here means the two surfaces interpret a name + type the
 * same way and can't drift (the same reason [MtgCommandRef] is shared).
 *
 * The mapping mirrors what a user would type on scryfall.com:
 *  - the name fragment becomes a quoted phrase (`"iron man"`), so Scryfall
 *    reads it as a name-includes-this-phrase match rather than loose words;
 *  - each whitespace-separated type becomes its own `t:` term, ANDed together
 *    (`legendary creature` → `t:legendary t:creature`);
 *  - a raw query is appended verbatim, so power users can AND in anything
 *    Scryfall understands (`c:r mv<=2`).
 *
 * Blank parts drop out; all-blank yields an empty string, which callers treat
 * as "nothing to search".
 */
object MtgSearchQuery {

    fun build(name: String?, type: String?, raw: String?): String = buildList {
        name?.takeIf { it.isNotBlank() }?.let { add("\"${it.trim().replace("\"", "")}\"") }
        type?.takeIf { it.isNotBlank() }
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.forEach { add("t:$it") }
        raw?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
    }.joinToString(" ")
}
