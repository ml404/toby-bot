package common.mtg

/**
 * Checks whether a resolved card pool is legal in a given format, using each
 * card's Scryfall [CubeCard.legalities] status. Pure maths shared by the web
 * deck checker and the Discord command so the two surfaces agree.
 *
 * A deck is **legal** when no card is banned or not-in-format. Restricted cards
 * (Vintage) are legal but limited to one copy — flagged separately rather than
 * failing the deck, since we check the card list, not per-copy counts.
 */
object DeckLegality {

    /** How a single card sits in the chosen format. */
    enum class Status { LEGAL, NOT_LEGAL, BANNED, RESTRICTED, UNKNOWN }

    /** One card's name and its status in the format. */
    data class CardStatus(val name: String, val status: Status)

    /**
     * The verdict for a format: [legal] is true only when nothing is banned or
     * not-in-format. The buckets list the offending/flagged cards by name
     * (deduped, input order preserved).
     */
    data class Report(
        val format: String,
        val legal: Boolean,
        val banned: List<String>,
        val notLegal: List<String>,
        val restricted: List<String>,
        val unknown: List<String>,
        val total: Int,
    )

    /** Maps a raw Scryfall status string to a [Status]. */
    fun statusOf(raw: String?): Status = when (raw) {
        "legal" -> Status.LEGAL
        "not_legal" -> Status.NOT_LEGAL
        "banned" -> Status.BANNED
        "restricted" -> Status.RESTRICTED
        else -> Status.UNKNOWN
    }

    /**
     * Checks [cards] against the format with Scryfall code [formatCode]
     * (e.g. "modern"). Cards dedupe by name so a deck with four copies of a
     * banned card reports it once. The deck is legal only when nothing is
     * banned or not-in-format; restricted and unknown cards don't fail it but
     * are surfaced.
     */
    fun check(cards: List<CubeCard>, formatCode: String): Report {
        val banned = LinkedHashSet<String>()
        val notLegal = LinkedHashSet<String>()
        val restricted = LinkedHashSet<String>()
        val unknown = LinkedHashSet<String>()
        cards.forEach { card ->
            when (statusOf(card.legalities[formatCode])) {
                Status.BANNED -> banned += card.name
                Status.NOT_LEGAL -> notLegal += card.name
                Status.RESTRICTED -> restricted += card.name
                Status.UNKNOWN -> unknown += card.name
                Status.LEGAL -> {}
            }
        }
        return Report(
            format = formatCode,
            legal = banned.isEmpty() && notLegal.isEmpty(),
            banned = banned.toList(),
            notLegal = notLegal.toList(),
            restricted = restricted.toList(),
            unknown = unknown.toList(),
            total = cards.size,
        )
    }
}
