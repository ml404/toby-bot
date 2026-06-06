package common.mtg

/**
 * A card's official rulings (Scryfall `/cards/:id/rulings`) — the clarifying
 * notes the rules team publishes for tricky interactions. A pure value type
 * shared by the bot's `/cube rulings` command and the web card-lookup tool,
 * both of which fetch from Scryfall (over their own HTTP clients) and map the
 * JSON into this. Presentation-only — no maths depend on it.
 *
 * [rulings] is empty when the card exists but has no published rulings, which
 * each surface presents as a friendly "no rulings yet" state rather than an
 * error.
 */
data class CardRulings(
    val cardName: String,
    val scryfallUri: String?,
    val rulings: List<Ruling>,
) {
    /** One published ruling: when it was published (ISO date) and the note itself. */
    data class Ruling(val publishedAt: String, val comment: String)
}
