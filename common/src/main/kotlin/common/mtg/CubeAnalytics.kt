package common.mtg

/**
 * The "cube report": curve, card-type mix, rarity mix, average mana value,
 * and singleton/duplicate detection over a card pool. Pure maths shared by
 * the web tool and the Discord command (like [AsFan], which it delegates to
 * for the per-pack numbers) so the two surfaces can't drift.
 *
 * Lands are excluded from the mana curve and the average — a land's mana
 * value is 0 and would crush both — but they still appear in the type and
 * rarity breakdowns.
 */
object CubeAnalytics {

    /** Count of nonland cards at a mana value. [label] is "0".."6" or "7+". */
    data class ManaCurveBucket(val label: String, val count: Int)

    /** Count and as-fan-per-pack for a single [CardType]. */
    data class TypeCount(val type: CardType, val count: Int, val asFan: Double)

    /** Count and as-fan-per-pack for a single [Rarity]. */
    data class RarityCount(val rarity: Rarity, val count: Int, val asFan: Double)

    /** A non-basic card name appearing more than once (a singleton violation). */
    data class Duplicate(val name: String, val count: Int)

    /** Count of two-colour cards supporting a guild, e.g. "Azorius (WU)". */
    data class ColorPairCount(val pair: String, val count: Int)

    /** Coloured mana pips of one colour across the whole cube (the true colour weight). */
    data class ColorPipCount(val color: String, val count: Int)

    /** The whole report, assembled once so the surfaces render identical numbers. */
    data class Analytics(
        val curve: List<ManaCurveBucket>,
        val averageManaValue: Double,
        val nonLandCount: Int,
        val types: List<TypeCount>,
        val rarities: List<RarityCount>,
        val duplicates: List<Duplicate>,
        val colorPairs: List<ColorPairCount>,
        val colorPips: List<ColorPipCount>,
    )

    /** The highest mana-value bucket; everything at or above it folds into "7+". */
    const val CURVE_TOP = 7

    /** The ten guilds, in allied-then-enemy order, for the colour-pair breakdown. */
    private val GUILDS: List<Pair<Set<MtgColor>, String>> = listOf(
        setOf(MtgColor.WHITE, MtgColor.BLUE) to "Azorius",
        setOf(MtgColor.BLUE, MtgColor.BLACK) to "Dimir",
        setOf(MtgColor.BLACK, MtgColor.RED) to "Rakdos",
        setOf(MtgColor.RED, MtgColor.GREEN) to "Gruul",
        setOf(MtgColor.GREEN, MtgColor.WHITE) to "Selesnya",
        setOf(MtgColor.WHITE, MtgColor.BLACK) to "Orzhov",
        setOf(MtgColor.BLUE, MtgColor.RED) to "Izzet",
        setOf(MtgColor.BLACK, MtgColor.GREEN) to "Golgari",
        setOf(MtgColor.RED, MtgColor.WHITE) to "Boros",
        setOf(MtgColor.GREEN, MtgColor.BLUE) to "Simic",
    )

    private val PIP = Regex("\\{([^}]+)\\}")

    fun analyze(cards: List<CubeCard>, packSize: Int): Analytics = Analytics(
        curve = manaCurve(cards),
        averageManaValue = averageManaValue(cards),
        nonLandCount = cards.count { !it.isLand },
        types = typeCounts(cards, packSize),
        rarities = rarityCounts(cards, packSize),
        duplicates = duplicates(cards),
        colorPairs = colorPairs(cards),
        colorPips = colorPips(cards),
    )

    /**
     * Two-colour cards grouped by guild, in guild order, present pairs only —
     * how well each two-colour archetype is supported. Includes dual lands
     * (they're fixing for that pair).
     */
    fun colorPairs(cards: List<CubeCard>): List<ColorPairCount> {
        val twoColor = cards.filter { it.colors.size == 2 }
        return GUILDS.mapNotNull { (set, name) ->
            val count = twoColor.count { it.colors == set }
            if (count > 0) ColorPairCount("$name (${pairCode(set)})", count) else null
        }
    }

    /** The WUBRG-ordered symbol code for a colour set, e.g. {W,U} → "WU". */
    private fun pairCode(colors: Set<MtgColor>): String =
        MtgColor.entries.filter { it in colors }.joinToString("") { it.symbol.toString() }

    /**
     * Coloured mana pips per colour across every card's mana cost — the cube's
     * true colour weight, which card counts alone miss. Hybrid pips ({W/U})
     * count toward both colours; generic/Phyrexian/colourless symbols are
     * ignored. WUBRG order, present colours only.
     */
    fun colorPips(cards: List<CubeCard>): List<ColorPipCount> {
        val counts = linkedMapOf<MtgColor, Int>()
        cards.forEach { card ->
            val cost = card.manaCost ?: return@forEach
            PIP.findAll(cost).forEach { match ->
                match.groupValues[1].forEach { ch ->
                    MtgColor.fromSymbol(ch)?.let { counts[it] = (counts[it] ?: 0) + 1 }
                }
            }
        }
        return MtgColor.entries
            .filter { counts[it] != null }
            .map { ColorPipCount(it.displayName, counts.getValue(it)) }
    }

    /**
     * The mana curve of the nonland cards: always all eight buckets
     * (0..6, 7+) including empty ones, so a chart shows the full shape.
     * Fractional mana values (un-cards) truncate toward the lower bucket.
     */
    fun manaCurve(cards: List<CubeCard>): List<ManaCurveBucket> {
        val counts = IntArray(CURVE_TOP + 1)
        cards.asSequence()
            .filter { !it.isLand }
            .forEach { counts[it.manaValue.toInt().coerceIn(0, CURVE_TOP)]++ }
        return counts.mapIndexed { mv, count ->
            ManaCurveBucket(if (mv == CURVE_TOP) "$CURVE_TOP+" else mv.toString(), count)
        }
    }

    /** Mean mana value of the nonland cards, or 0.0 when there are none. */
    fun averageManaValue(cards: List<CubeCard>): Double {
        val nonland = cards.filter { !it.isLand }
        if (nonland.isEmpty()) return 0.0
        return nonland.sumOf { it.manaValue } / nonland.size
    }

    /** Count + as-fan per [CardType] present in the pool, in enum order. */
    fun typeCounts(cards: List<CubeCard>, packSize: Int): List<TypeCount> {
        if (cards.isEmpty()) return emptyList()
        val counts = cards.groupingBy { CardType.of(it.typeLine) }.eachCount()
        return CardType.entries
            .filter { counts[it] != null }
            .map { type -> TypeCount(type, counts.getValue(type), AsFan.value(counts.getValue(type), cards.size, packSize)) }
    }

    /** Count + as-fan per [Rarity] present in the pool, in enum order. */
    fun rarityCounts(cards: List<CubeCard>, packSize: Int): List<RarityCount> {
        if (cards.isEmpty()) return emptyList()
        val counts = cards.groupingBy { Rarity.parse(it.rarity) }.eachCount()
        return Rarity.entries
            .filter { counts[it] != null }
            .map { rarity -> RarityCount(rarity, counts.getValue(rarity), AsFan.value(counts.getValue(rarity), cards.size, packSize)) }
    }

    /**
     * Non-basic card names appearing more than once — a singleton cube
     * shouldn't have any. Basics (incl. Snow-Covered and Wastes) are allowed
     * duplicates, told apart by their type line, not a name list. Sorted by
     * count (most-duplicated first), then name.
     */
    fun duplicates(cards: List<CubeCard>): List<Duplicate> =
        cards.asSequence()
            .filterNot { CubeCard.isBasicType(it.typeLine) }
            .groupingBy { it.name }.eachCount()
            .filter { it.value > 1 }
            .map { Duplicate(it.key, it.value) }
            .sortedWith(compareByDescending<Duplicate> { it.count }.thenBy { it.name })
}
