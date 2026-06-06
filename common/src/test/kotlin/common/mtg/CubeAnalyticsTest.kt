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
    ) = CubeCard(
        name = name,
        isLand = CubeCard.isLandType(typeLine),
        typeLine = typeLine,
        manaValue = manaValue,
        rarity = rarity,
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
    }

    @Test
    fun `analyze on an empty pool does not throw`() {
        val a = CubeAnalytics.analyze(emptyList(), packSize = 15)
        assertEquals(0, a.nonLandCount)
        assertEquals(0.0, a.averageManaValue, 1e-9)
        assertTrue(a.types.isEmpty())
        assertTrue(a.rarities.isEmpty())
        assertTrue(a.duplicates.isEmpty())
        assertEquals(8, a.curve.size) // still the eight empty buckets
        assertTrue(a.curve.all { it.count == 0 })
    }
}
