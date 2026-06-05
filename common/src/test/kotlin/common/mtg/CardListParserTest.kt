package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardListParserTest {

    @Test
    fun `one name per line defaults to a count of one`() {
        val entries = CardListParser.parse("Lightning Bolt\nForest")
        assertEquals(listOf("Lightning Bolt", "Forest"), entries.map { it.name })
        assertEquals(listOf(1, 1), entries.map { it.count })
    }

    @Test
    fun `honours leading quantities like 3, 3x and 3X`() {
        assertEquals(CardListParser.Entry("Forest", 3), CardListParser.parse("3 Forest").single())
        assertEquals(CardListParser.Entry("Island", 7), CardListParser.parse("7x Island").single())
        assertEquals(CardListParser.Entry("Plains", 2), CardListParser.parse("2X Plains").single())
    }

    @Test
    fun `strips trailing set and collector tags`() {
        assertEquals(CardListParser.Entry("Lightning Bolt", 1), CardListParser.parse("1 Lightning Bolt (2X2) 117").single())
        assertEquals(CardListParser.Entry("Sol Ring", 1), CardListParser.parse("Sol Ring (CMR)").single())
    }

    @Test
    fun `ignores blank lines and comments`() {
        val entries = CardListParser.parse(
            """
            # My cube
            1 Bolt

            // sideboard
            Shock
            """.trimIndent()
        )
        assertEquals(listOf("Bolt", "Shock"), entries.map { it.name })
    }

    @Test
    fun `strips a trailing finish marker even without a set tag`() {
        assertEquals(CardListParser.Entry("Sol Ring", 1), CardListParser.parse("Sol Ring *F*").single())
        assertEquals(CardListParser.Entry("Lightning Bolt", 4), CardListParser.parse("4 Lightning Bolt (2X2) 117 *F*").single())
    }

    @Test
    fun `skips exporter section headers`() {
        val entries = CardListParser.parse(
            """
            Deck
            4 Lightning Bolt (2X2) 117
            1 Sol Ring (C21) 263

            Sideboard
            2 Shock
            Commander
            Companion
            """.trimIndent()
        )
        assertEquals(listOf("Lightning Bolt", "Sol Ring", "Shock"), entries.map { it.name })
        assertEquals(listOf(4, 1, 2), entries.map { it.count })
    }

    @Test
    fun `section-header words still parse as cards when given a quantity`() {
        // "1 Commander" is a (hypothetical) card line, not a header.
        assertEquals(CardListParser.Entry("Commander", 1), CardListParser.parse("1 Commander").single())
    }

    @Test
    fun `header matching is case-insensitive`() {
        assertTrue(CardListParser.parse("SIDEBOARD\ndeck\nCommander").isEmpty())
    }

    @Test
    fun `caps an absurd quantity`() {
        assertEquals(CardListParser.MAX_PER_NAME, CardListParser.parse("99999 Forest").single().count)
    }

    @Test
    fun `blank input yields no entries`() {
        assertTrue(CardListParser.parse("   \n\n  ").isEmpty())
    }
}
