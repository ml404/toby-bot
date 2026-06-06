package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeAnalyticsTest {

    private fun card(
        name: String,
        typeLine: String = "Creature",
        manaValue: Double = 1.0,
        rarity: String? = "common",
        colors: Set<MtgColor> = emptySet(),
        manaCost: String? = null,
        priceUsd: String? = null,
        priceEur: String? = null,
        priceTix: String? = null,
    ) = CubeCard(
        name = name,
        colors = colors,
        isLand = CubeCard.isLandType(typeLine),
        typeLine = typeLine,
        manaValue = manaValue,
        rarity = rarity,
        manaCost = manaCost,
        priceUsd = priceUsd,
        priceEur = priceEur,
        priceTix = priceTix,
    )

    // --- mana curve ----------------------------------------------------

    @Test
    fun `mana curve always emits all eight buckets, lands excluded`() {
        val pool = listOf(
            card("A", "Instant", 0.0),
            card("B", "Creature", 2.0),
            card("C", "Creature", 2.0),
            card("Forest", "Basic Land — Forest", 0.0),
        )
        val curve = CubeAnalytics.manaCurve(pool)
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7+"), curve.map { it.label })
        assertEquals(1, curve.first { it.label == "0" }.count) // the land at 0 is excluded → only the instant
        assertEquals(2, curve.first { it.label == "2" }.count)
        assertEquals(0, curve.first { it.label == "5" }.count)
    }

    @Test
    fun `the top bucket gathers everything at or above seven`() {
        val pool = listOf(
            card("Seven", "Creature", 7.0),
            card("Eight", "Creature", 8.0),
            card("Twelve", "Creature", 12.0),
            card("Six", "Creature", 6.0),
        )
        val curve = CubeAnalytics.manaCurve(pool)
        assertEquals(3, curve.first { it.label == "7+" }.count)
        assertEquals(1, curve.first { it.label == "6" }.count)
    }

    @Test
    fun `fractional mana values truncate toward the lower bucket`() {
        val pool = listOf(card("Half", "Instant", 0.5), card("ThreeAndHalf", "Instant", 3.5))
        val curve = CubeAnalytics.manaCurve(pool)
        assertEquals(1, curve.first { it.label == "0" }.count)
        assertEquals(1, curve.first { it.label == "3" }.count)
    }

    // --- average mana value --------------------------------------------

    @Test
    fun `average mana value is the nonland mean`() {
        val pool = listOf(
            card("A", "Instant", 1.0),
            card("B", "Creature", 3.0),
            card("Forest", "Basic Land — Forest", 0.0),
        )
        assertEquals(2.0, CubeAnalytics.averageManaValue(pool), 1e-9)
    }

    @Test
    fun `average mana value is zero for an all-land pool`() {
        val pool = listOf(card("Forest", "Basic Land — Forest", 0.0), card("Island", "Basic Land — Island", 0.0))
        assertEquals(0.0, CubeAnalytics.averageManaValue(pool), 1e-9)
    }

    // --- type breakdown ------------------------------------------------

    @Test
    fun `type counts bucket by dominant type with as-fan, in enum order`() {
        val pool = listOf(
            card("Bear", "Creature — Bear", 2.0),
            card("Golem", "Artifact Creature — Golem", 4.0),
            card("Bolt", "Instant", 1.0),
            card("Sol Ring", "Artifact", 1.0),
            card("Forest", "Basic Land — Forest", 0.0),
        )
        val types = CubeAnalytics.typeCounts(pool, packSize = 5)
        assertEquals(listOf("Creature", "Instant", "Artifact", "Land"), types.map { it.type.displayName })
        val creature = types.first { it.type == CardType.CREATURE }
        assertEquals(2, creature.count)
        // (2 / 5) * 5 = 2.0
        assertEquals(2.0, creature.asFan, 1e-9)
    }

    @Test
    fun `type counts on an empty pool is empty and never divides by zero`() {
        assertTrue(CubeAnalytics.typeCounts(emptyList(), packSize = 15).isEmpty())
    }

    // --- rarity breakdown ----------------------------------------------

    @Test
    fun `rarity counts group by parsed rarity, unknowns into Other`() {
        val pool = listOf(
            card("A", rarity = "common"),
            card("B", rarity = "common"),
            card("C", rarity = "mythic"),
            card("D", rarity = null),
            card("E", rarity = "special"),
        )
        val rarities = CubeAnalytics.rarityCounts(pool, packSize = 5)
        assertEquals(listOf("Common", "Mythic", "Other"), rarities.map { it.rarity.displayName })
        assertEquals(2, rarities.first { it.rarity == Rarity.COMMON }.count)
        assertEquals(2, rarities.first { it.rarity == Rarity.OTHER }.count) // null + special
    }

    // --- duplicates ----------------------------------------------------

    @Test
    fun `duplicates flag repeated non-basic cards, sorted by count then name`() {
        val pool = listOf(
            card("Sol Ring", "Artifact", 1.0),
            card("Sol Ring", "Artifact", 1.0),
            card("Mana Crypt", "Artifact", 0.0),
            card("Mana Crypt", "Artifact", 0.0),
            card("Mana Crypt", "Artifact", 0.0),
            card("Lightning Bolt", "Instant", 1.0),
        )
        val dupes = CubeAnalytics.duplicates(pool)
        assertEquals(listOf("Mana Crypt", "Sol Ring"), dupes.map { it.name }) // 3 before 2
        assertEquals(3, dupes.first { it.name == "Mana Crypt" }.count)
    }

    @Test
    fun `basics, snow-covered basics and wastes are allowed duplicates`() {
        val pool = listOf(
            card("Forest", "Basic Land — Forest", 0.0),
            card("Forest", "Basic Land — Forest", 0.0),
            card("Snow-Covered Island", "Basic Snow Land — Island", 0.0),
            card("Snow-Covered Island", "Basic Snow Land — Island", 0.0),
            card("Wastes", "Basic Land", 0.0),
            card("Wastes", "Basic Land", 0.0),
        )
        assertTrue(CubeAnalytics.duplicates(pool).isEmpty())
    }

    // --- colour pairs (guilds) -----------------------------------------

    @Test
    fun `colorPairs counts two-colour cards by guild, in guild order, present only`() {
        val pool = listOf(
            card("Teferi", colors = setOf(MtgColor.WHITE, MtgColor.BLUE)),
            card("Dovin", colors = setOf(MtgColor.WHITE, MtgColor.BLUE)),
            card("Hadana", colors = setOf(MtgColor.GREEN, MtgColor.BLUE)), // Simic
            // A dual land counts toward its pair — it's fixing for that archetype.
            card("Hallowed Fountain", typeLine = "Land — Plains Island", colors = setOf(MtgColor.WHITE, MtgColor.BLUE)),
            card("Bolt", colors = setOf(MtgColor.RED)), // mono — ignored
            card("Niv", colors = MtgColor.entries.toSet()), // 5c — ignored
        )
        val pairs = CubeAnalytics.colorPairs(pool)
        // Codes are WUBRG-canonical, so Simic {G,U} renders as "UG".
        assertEquals(listOf("Azorius (WU)", "Simic (UG)"), pairs.map { it.pair }) // guild order
        assertEquals(3, pairs.first { it.pair.startsWith("Azorius") }.count) // 2 spells + the dual land
    }

    // --- colour pips ---------------------------------------------------

    @Test
    fun `colorPips counts coloured pips from mana costs, hybrid counts both`() {
        val pool = listOf(
            card("A", manaCost = "{1}{W}{W}"),       // W,W
            card("B", manaCost = "{U}{B}"),          // U,B
            card("C", manaCost = "{W/U}"),           // hybrid → W and U
            card("D", manaCost = "{2}{R/P}"),        // Phyrexian red → R (generic/P ignored)
            card("E", manaCost = null),              // no cost — skipped
            card("Forest", typeLine = "Basic Land — Forest", manaCost = ""), // no pips
        )
        val pips = CubeAnalytics.colorPips(pool).associate { it.color to it.count }
        assertEquals(3, pips["White"]) // WW + hybrid W
        assertEquals(2, pips["Blue"]) // U from card B + U from the hybrid
        assertEquals(1, pips["Black"])
        assertEquals(1, pips["Red"])
        assertEquals(null, pips["Green"]) // absent → not listed
    }

    @Test
    fun `colorPips ignores generic, colourless, snow, variable and two-brid generic`() {
        val pool = listOf(
            card("Genericish", manaCost = "{2}{C}{S}{X}{W}"), // only the W is a coloured pip
            card("Twobrid", manaCost = "{2/W}{2/U}"),         // two-brid → the W and U halves count
        )
        val pips = CubeAnalytics.colorPips(pool).associate { it.color to it.count }
        assertEquals(2, pips["White"]) // {W} + {2/W}
        assertEquals(1, pips["Blue"]) // {2/U}
        assertEquals(null, pips["Black"])
        assertEquals(null, pips["Green"])
    }

    @Test
    fun `colorPips lists colours in WUBRG order`() {
        val pool = listOf(card("X", manaCost = "{G}{R}{W}"))
        assertEquals(listOf("White", "Red", "Green"), CubeAnalytics.colorPips(pool).map { it.color })
    }

    // --- total value (per currency) ------------------------------------

    @Test
    fun `totalValues sums each currency, ignoring unpriced or unparseable cards`() {
        val pool = listOf(
            card("A", priceUsd = "1.50", priceEur = "1.20", priceTix = "0.03"),
            card("B", priceUsd = "2.25", priceEur = "2.00"),
            card("C", priceUsd = null),      // unpriced — ignored
            card("D", priceUsd = ""),        // blank — ignored
            card("E", priceUsd = "n/a"),     // not a number — ignored
        )
        val totals = CubeAnalytics.totalValues(pool).associate { it.currency to it.amount }
        assertEquals(3.75, totals[MtgCurrency.USD]!!, 1e-9)
        assertEquals(3.20, totals[MtgCurrency.EUR]!!, 1e-9)
        assertEquals(0.03, totals[MtgCurrency.TIX]!!, 1e-9)
    }

    @Test
    fun `totalValues lists currencies in enum order, omitting unpriced currencies`() {
        // Only EUR is priced here, so USD and Tix are absent entirely.
        val pool = listOf(card("A", priceEur = "5.00"))
        val totals = CubeAnalytics.totalValues(pool)
        assertEquals(listOf(MtgCurrency.EUR), totals.map { it.currency })
    }

    @Test
    fun `totalValues is empty when nothing in the pool is priced`() {
        val pool = listOf(card("A"), card("B", priceUsd = ""))
        assertTrue(CubeAnalytics.totalValues(pool).isEmpty())
    }

    @Test
    fun `Analytics totalValueIn resolves a currency or returns null`() {
        val a = CubeAnalytics.analyze(listOf(card("A", priceUsd = "4.00")), packSize = 1)
        assertEquals(4.00, a.totalValueIn(MtgCurrency.USD)!!, 1e-9)
        assertEquals(null, a.totalValueIn(MtgCurrency.EUR))
    }

    // --- analyze (aggregate) -------------------------------------------

    @Test
    fun `analyze assembles every section and counts nonland cards`() {
        val pool = listOf(
            card("Bolt", "Instant", 1.0, "common"),
            card("Bear", "Creature — Bear", 2.0, "common"),
            card("Forest", "Basic Land — Forest", 0.0, "common"),
        )
        val a = CubeAnalytics.analyze(pool, packSize = 3)
        assertEquals(8, a.curve.size)
        assertEquals(2, a.nonLandCount)
        assertEquals(1.5, a.averageManaValue, 1e-9)
        assertEquals(setOf("Creature", "Instant", "Land"), a.types.map { it.type.displayName }.toSet())
        assertTrue(a.duplicates.isEmpty())
        assertTrue(a.totalValues.isEmpty()) // none of these cards carry a price
    }

    @Test
    fun `analyze carries the per-currency totals when cards are priced`() {
        val pool = listOf(
            card("Bolt", "Instant", 1.0, priceUsd = "2.00", priceEur = "1.50"),
            card("Bear", "Creature — Bear", 2.0, priceUsd = "0.50", priceEur = "0.40"),
        )
        val a = CubeAnalytics.analyze(pool, packSize = 2)
        assertEquals(2.50, a.totalValueIn(MtgCurrency.USD)!!, 1e-9)
        assertEquals(1.90, a.totalValueIn(MtgCurrency.EUR)!!, 1e-9)
    }

    @Test
    fun `analyze on an empty pool does not throw`() {
        val a = CubeAnalytics.analyze(emptyList(), packSize = 15)
        assertEquals(0, a.nonLandCount)
        assertEquals(0.0, a.averageManaValue, 1e-9)
        assertTrue(a.types.isEmpty())
        assertTrue(a.rarities.isEmpty())
        assertTrue(a.duplicates.isEmpty())
        assertTrue(a.colorPairs.isEmpty())
        assertTrue(a.colorPips.isEmpty())
        assertEquals(8, a.curve.size) // still the eight empty buckets
        assertTrue(a.curve.all { it.count == 0 })
        assertTrue(a.totalValues.isEmpty())
    }
}
