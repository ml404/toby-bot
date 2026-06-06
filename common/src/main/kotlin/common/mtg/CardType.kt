package common.mtg

/**
 * The primary card type a cube-design report buckets cards by. Magic cards
 * can carry several types at once ("Artifact Creature", "Enchantment —
 * Saga"), so [of] picks a single dominant type by a fixed precedence rather
 * than listing them all.
 */
enum class CardType(val displayName: String) {
    CREATURE("Creature"),
    INSTANT("Instant"),
    SORCERY("Sorcery"),
    ARTIFACT("Artifact"),
    ENCHANTMENT("Enchantment"),
    PLANESWALKER("Planeswalker"),
    BATTLE("Battle"),
    LAND("Land"),
    OTHER("Other");

    companion object {
        /**
         * Classifies a Scryfall `type_line` by its **front face** (everything
         * before `//`, like [CubeCard.isLandType]) so a double-faced card is
         * bucketed by what's drafted and cast. First match in this precedence
         * wins:
         *
         *  1. **Land** — a land is fixing, not a spell, regardless of any other
         *     types ("Artifact Land", or Dryad Arbor's "Land Creature"). Mirrors
         *     [CubeCard.category], which buckets any land as LAND.
         *  2. **Planeswalker** / **Battle** — distinct buckets that never
         *     co-occur with Creature, checked first so they aren't swept into
         *     Other.
         *  3. **Creature** — so "Artifact Creature" / "Enchantment Creature"
         *     count as creatures (the dominant gameplay role; a body matters
         *     more than its supertypes for curve/role analysis).
         *  4. **Instant** / **Sorcery** / **Enchantment** / **Artifact**.
         *  5. **Other** — Tribal-only, Plane, Scheme, an empty type line, etc.
         */
        fun of(typeLine: String): CardType {
            val front = typeLine.substringBefore("//")
            fun has(word: String) = front.contains(word, ignoreCase = true)
            return when {
                has("Land") -> LAND
                has("Planeswalker") -> PLANESWALKER
                has("Battle") -> BATTLE
                has("Creature") -> CREATURE
                has("Instant") -> INSTANT
                has("Sorcery") -> SORCERY
                has("Enchantment") -> ENCHANTMENT
                has("Artifact") -> ARTIFACT
                else -> OTHER
            }
        }
    }
}
