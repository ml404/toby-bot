package common.mtg

/**
 * A card's printing rarity, for a cube's rarity-mix report. Scryfall's
 * `rarity` string maps onto these; the handful of non-standard values
 * (`special`, `bonus`) and anything unknown fold into [OTHER] so a report
 * never crashes on a surprise value.
 */
enum class Rarity(val displayName: String) {
    COMMON("Common"),
    UNCOMMON("Uncommon"),
    RARE("Rare"),
    MYTHIC("Mythic"),
    OTHER("Other");

    companion object {
        fun parse(raw: String?): Rarity = when (raw?.trim()?.lowercase()) {
            "common" -> COMMON
            "uncommon" -> UNCOMMON
            "rare" -> RARE
            "mythic" -> MYTHIC
            else -> OTHER // "special", "bonus", null, or anything unexpected
        }
    }
}
