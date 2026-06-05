package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CubeCardTest {

    @Test
    fun `mono-coloured card maps to its colour bucket`() {
        assertEquals(
            CardCategory.RED,
            CubeCard(name = "Lightning Bolt", colors = setOf(MtgColor.RED)).category,
        )
        assertEquals(
            CardCategory.WHITE,
            CubeCard(name = "Swords to Plowshares", colors = setOf(MtgColor.WHITE)).category,
        )
    }

    @Test
    fun `two or more colours map to multicolour`() {
        assertEquals(
            CardCategory.MULTICOLOR,
            CubeCard(name = "Lightning Helix", colors = setOf(MtgColor.RED, MtgColor.WHITE)).category,
        )
        assertEquals(
            CardCategory.MULTICOLOR,
            CubeCard(
                name = "Niv-Mizzet Reborn",
                colors = MtgColor.entries.toSet(),
            ).category,
        )
    }

    @Test
    fun `no colours maps to colourless`() {
        assertEquals(
            CardCategory.COLORLESS,
            CubeCard(name = "Sol Ring", colors = emptySet()).category,
        )
    }

    @Test
    fun `a land is bucketed as land even when it has a colour identity`() {
        // A dual land taps for colours but is still "fixing", not a spell.
        val dual = CubeCard(
            name = "Sacred Foundry",
            colors = setOf(MtgColor.RED, MtgColor.WHITE),
            isLand = true,
            typeLine = "Land — Mountain Plains",
        )
        assertEquals(CardCategory.LAND, dual.category)
    }

    @Test
    fun `colourless land is still a land`() {
        assertEquals(
            CardCategory.LAND,
            CubeCard(name = "Wasteland", isLand = true).category,
        )
    }

    @Test
    fun `ofColor covers every colour`() {
        MtgColor.entries.forEach { color ->
            // Round-trips: a mono-card of this colour lands in ofColor's bucket.
            val card = CubeCard(name = color.displayName, colors = setOf(color))
            assertEquals(CardCategory.ofColor(color), card.category)
        }
    }

    @Test
    fun `defaults describe a colourless zero-cost non-land`() {
        val card = CubeCard(name = "Placeholder")
        assertEquals(CardCategory.COLORLESS, card.category)
        assertEquals(0.0, card.manaValue)
        assertEquals(false, card.isLand)
    }
}
