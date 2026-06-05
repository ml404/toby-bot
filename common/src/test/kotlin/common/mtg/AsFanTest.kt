package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsFanTest {

    @Test
    fun `worked example from the doc - 60 removal in 540 cube, 15-card packs`() {
        // (60 / 540) × 15 = 1.666…
        assertEquals(1.6667, AsFan.value(60, 540, 15), 1e-4)
    }

    @Test
    fun `as-fan of 2_0 means about two per pack`() {
        // 72 / 540 × 15 = 2.0 exactly.
        assertEquals(2.0, AsFan.value(72, 540, 15), 1e-9)
    }

    @Test
    fun `zero of a type is zero as-fan`() {
        assertEquals(0.0, AsFan.value(0, 540, 15), 1e-9)
    }

    @Test
    fun `every card of the type yields as-fan equal to pack size`() {
        assertEquals(15.0, AsFan.value(540, 540, 15), 1e-9)
    }

    @Test
    fun `non-positive cube size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(1, 0, 15) }
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(1, -5, 15) }
    }

    @Test
    fun `non-positive pack size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(1, 540, 0) }
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(1, 540, -1) }
    }

    @Test
    fun `negative type count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(-1, 540, 15) }
    }

    @Test
    fun `type count above cube size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AsFan.value(541, 540, 15) }
    }

    @Test
    fun `categoryCounts tallies each bucket`() {
        val counts = AsFan.categoryCounts(samplePool())
        assertEquals(2, counts[CardCategory.RED])
        assertEquals(1, counts[CardCategory.WHITE])
        assertEquals(1, counts[CardCategory.MULTICOLOR])
        assertEquals(1, counts[CardCategory.COLORLESS])
        assertEquals(1, counts[CardCategory.LAND])
    }

    @Test
    fun `distribution divides each bucket by the cube size times pack size`() {
        val pool = samplePool() // 6 cards
        val dist = AsFan.distribution(pool, packSize = 3)
        // Red: 2/6 × 3 = 1.0; Land: 1/6 × 3 = 0.5
        assertEquals(1.0, dist.getValue(CardCategory.RED), 1e-9)
        assertEquals(0.5, dist.getValue(CardCategory.LAND), 1e-9)
        assertEquals(0.5, dist.getValue(CardCategory.WHITE), 1e-9)
    }

    @Test
    fun `distribution only includes categories present in the pool`() {
        val dist = AsFan.distribution(samplePool(), packSize = 3)
        assertTrue(CardCategory.BLUE !in dist)
        assertTrue(CardCategory.GREEN !in dist)
    }

    @Test
    fun `distribution sums to pack size across all categories`() {
        val dist = AsFan.distribution(samplePool(), packSize = 3)
        // Every card belongs to exactly one bucket, so the as-fans must
        // total the pack size.
        assertEquals(3.0, dist.values.sum(), 1e-9)
    }

    @Test
    fun `distribution of an empty pool is empty`() {
        assertTrue(AsFan.distribution(emptyList(), packSize = 15).isEmpty())
    }

    private fun samplePool(): List<CubeCard> = listOf(
        CubeCard("Bolt", setOf(MtgColor.RED)),
        CubeCard("Shock", setOf(MtgColor.RED)),
        CubeCard("Swords", setOf(MtgColor.WHITE)),
        CubeCard("Helix", setOf(MtgColor.RED, MtgColor.WHITE)),
        CubeCard("Sol Ring"),
        CubeCard("Wasteland", isLand = true),
    )
}
