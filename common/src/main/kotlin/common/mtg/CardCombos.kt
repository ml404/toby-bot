package common.mtg

/**
 * The combos a card takes part in, from the Commander Spellbook database — the
 * pieces ("uses"), the payoff ("produces"), and a link to the full write-up.
 * A pure value type shared by the bot's `/cube combos` command and the web
 * card-lookup tool, both of which fetch from Commander Spellbook (over their
 * own HTTP clients) and map the JSON into this. Presentation-only.
 *
 * [combos] is empty when the card exists but is in no known combos, which each
 * surface presents as a friendly "no combos" state rather than an error.
 */
data class CardCombos(
    val cardName: String,
    val combos: List<Combo>,
) {
    /**
     * One combo: the cards it [uses], the results it [produces], and the
     * Commander Spellbook [url] for the full step-by-step.
     */
    data class Combo(
        val id: String,
        val uses: List<String>,
        val produces: List<String>,
        val url: String,
    )

    companion object {
        /** The public Commander Spellbook page for a combo id. */
        fun comboUrl(id: String): String = "https://commanderspellbook.com/combo/$id/"
    }
}
