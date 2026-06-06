package common.mtg

/**
 * A small built-in glossary of Magic keywords and their reminder text — the
 * data behind `/mtg rule` and its web twin. Curated (evergreen keywords plus
 * a handful of the most-asked-about non-evergreen ones) rather than fetched, so
 * it needs no external service and answers instantly. Beginner-friendly, which
 * broadens the bot's appeal beyond cube builders.
 *
 * Reminder text is paraphrased from the comprehensive rules / Oracle reminder
 * text; it's a quick refresher, not a rules ruling (use `/mtgcard rulings` for
 * card-specific official rulings).
 */
object MtgGlossary {

    /** One glossary entry: the [keyword] as displayed and its reminder [text]. */
    data class Term(val keyword: String, val text: String)

    private val TERMS: List<Term> = listOf(
        Term("Deathtouch", "Any amount of damage this deals to a creature is enough to destroy it."),
        Term("Defender", "This creature can't attack."),
        Term("Double strike", "This creature deals both first-strike and regular combat damage."),
        Term("Enchant", "This Aura can only be attached to the kind of permanent its Enchant ability names."),
        Term("Equip", "Attach this Equipment to target creature you control. Equip only as a sorcery."),
        Term("First strike", "This creature deals combat damage before creatures without first strike."),
        Term("Flash", "You may cast this spell any time you could cast an instant."),
        Term("Flying", "This creature can't be blocked except by creatures with flying or reach."),
        Term("Haste", "This creature can attack and use tap abilities as soon as it comes under your control."),
        Term("Hexproof", "This permanent can't be the target of spells or abilities your opponents control."),
        Term("Indestructible", "Damage and effects that say \"destroy\" don't destroy this permanent."),
        Term("Lifelink", "Damage dealt by this creature also causes you to gain that much life."),
        Term("Menace", "This creature can't be blocked except by two or more creatures."),
        Term("Protection", "This can't be blocked, targeted, dealt damage, enchanted, or equipped by anything with the stated quality."),
        Term("Reach", "This creature can block creatures with flying."),
        Term("Trample", "This creature can deal excess combat damage to the player or planeswalker it's attacking."),
        Term("Vigilance", "Attacking doesn't cause this creature to tap."),
        Term("Ward", "Whenever this becomes the target of a spell or ability an opponent controls, counter it unless that player pays the ward cost."),
        Term("Prowess", "Whenever you cast a noncreature spell, this creature gets +1/+1 until end of turn."),
        Term("Scry", "Look at the top N cards of your library, then put any number of them on the bottom and the rest back on top in any order."),
        Term("Surveil", "Look at the top N cards of your library, then put any number into your graveyard and the rest back on top in any order."),
        Term("Mill", "A player puts the top N cards of their library into their graveyard."),
        Term("Fight", "Each of two creatures deals damage equal to its power to the other."),
        Term("Cascade", "When you cast this spell, exile cards from the top of your library until you exile a cheaper nonland card; you may cast it for free."),
        Term("Convoke", "Your creatures can help cast this spell. Each creature you tap pays for {1} or one mana of that creature's colour."),
        Term("Cycling", "Pay the cycling cost and discard this card to draw a card."),
        Term("Delve", "Each card you exile from your graveyard while casting this pays for {1}."),
        Term("Flashback", "You may cast this card from your graveyard for its flashback cost, then exile it."),
        Term("Kicker", "You may pay an additional cost as you cast this spell for an extra effect."),
        Term("Storm", "When you cast this spell, copy it for each spell cast before it this turn."),
        Term("Adventure", "You may cast the Adventure first; if you do, exile it afterwards and cast the creature later from exile."),
        Term("Disturb", "You may cast this card from your graveyard transformed for its disturb cost."),
        Term("Affinity", "This spell costs {1} less to cast for each of the named permanent you control."),
        Term("Annihilator", "Whenever this creature attacks, the defending player sacrifices that many permanents."),
    )

    private val BY_KEY: Map<String, Term> = TERMS.associateBy { it.keyword.lowercase() }

    /** Every term, in display order. */
    fun all(): List<Term> = TERMS

    /**
     * Resolves a query to a term: an exact (case-insensitive) keyword match
     * first, then a prefix match (so "death" finds Deathtouch), or null.
     */
    fun lookup(query: String): Term? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        BY_KEY[q]?.let { return it }
        return TERMS.firstOrNull { it.keyword.lowercase().startsWith(q) }
    }
}
