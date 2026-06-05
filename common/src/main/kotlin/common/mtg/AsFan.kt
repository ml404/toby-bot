package common.mtg

/**
 * "As-fan" — the expected number of cards of a given characteristic a
 * player opens in a single booster pack. Straight from the cube-design
 * doc:
 *
 *   as-fan = (cards of that type ÷ cube size) × pack size
 *
 * Example: 60 removal spells in a 540-card cube with 15-card packs →
 * (60 / 540) × 15 ≈ 1.67 removal spells per pack.
 *
 * Pure maths so the Discord command, the web tool, and the pack
 * generator all agree on the number.
 */
object AsFan {

    /**
     * Core formula. [typeCount] cards of some characteristic in a
     * [cubeSize]-card cube, opened [packSize] at a time.
     *
     * @throws IllegalArgumentException if the inputs can't describe a real
     *   cube (non-positive cube/pack size, negative or oversized type count).
     */
    fun value(typeCount: Int, cubeSize: Int, packSize: Int): Double {
        require(cubeSize > 0) { "Cube size must be positive (was $cubeSize)" }
        require(packSize > 0) { "Pack size must be positive (was $packSize)" }
        require(typeCount >= 0) { "Type count can't be negative (was $typeCount)" }
        require(typeCount <= cubeSize) {
            "Type count ($typeCount) can't exceed cube size ($cubeSize)"
        }
        return typeCount.toDouble() / cubeSize.toDouble() * packSize.toDouble()
    }

    /** Count of cards in each [CardCategory] present in the pool. */
    fun categoryCounts(cards: List<CubeCard>): Map<CardCategory, Int> =
        cards.groupingBy { it.category }.eachCount()

    /**
     * As-fan per [CardCategory] across the whole pool — i.e. how many
     * White cards, Lands, etc. a player expects to open per pack. Only
     * categories actually present in the pool appear in the result.
     */
    fun distribution(cards: List<CubeCard>, packSize: Int): Map<CardCategory, Double> {
        if (cards.isEmpty()) return emptyMap()
        val cubeSize = cards.size
        return categoryCounts(cards).mapValues { (_, count) ->
            value(count, cubeSize, packSize)
        }
    }
}
