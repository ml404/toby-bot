package common.mtg

/**
 * A Magic set's headline facts (Scryfall `/sets/:code`) — for the `/mtg set`
 * quick-reference command and its web twin. A pure value type; both surfaces
 * fetch from Scryfall and map the JSON into this.
 */
data class MtgSet(
    val code: String,
    val name: String,
    val setType: String,
    val releasedAt: String?,
    val cardCount: Int,
    val iconUrl: String?,
    val scryfallUri: String?,
)
