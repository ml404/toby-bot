package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MtgColorTest {

    @Test
    fun `every colour has the canonical mana symbol`() {
        assertEquals('W', MtgColor.WHITE.symbol)
        assertEquals('U', MtgColor.BLUE.symbol)
        assertEquals('B', MtgColor.BLACK.symbol)
        assertEquals('R', MtgColor.RED.symbol)
        assertEquals('G', MtgColor.GREEN.symbol)
    }

    @Test
    fun `fromSymbol resolves each colour, case-insensitively`() {
        assertEquals(MtgColor.WHITE, MtgColor.fromSymbol('W'))
        assertEquals(MtgColor.WHITE, MtgColor.fromSymbol('w'))
        assertEquals(MtgColor.GREEN, MtgColor.fromSymbol('g'))
    }

    @Test
    fun `fromSymbol returns null for an unknown symbol`() {
        assertNull(MtgColor.fromSymbol('X'))
        assertNull(MtgColor.fromSymbol('1'))
    }

    @Test
    fun `parse maps a Scryfall colour array to a set`() {
        assertEquals(setOf(MtgColor.WHITE, MtgColor.BLUE), MtgColor.parse(listOf("W", "U")))
    }

    @Test
    fun `parse tolerates lowercase, whitespace and multi-char entries`() {
        assertEquals(
            setOf(MtgColor.RED, MtgColor.GREEN),
            MtgColor.parse(listOf(" r ", "Green")),
        )
    }

    @Test
    fun `parse drops unrecognised symbols and dedupes`() {
        assertEquals(setOf(MtgColor.BLACK), MtgColor.parse(listOf("B", "X", "b", "")))
    }

    @Test
    fun `parse of an empty array is an empty set`() {
        assertTrue(MtgColor.parse(emptyList()).isEmpty())
    }
}
