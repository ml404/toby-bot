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
    fun `skips the pack dividers a downloaded pack list carries`() {
        // Pasting a downloaded deal straight into the card list box used to
        // report every "== Pack N ==" header as a card it couldn't find.
        val entries = CardListParser.parse(
            """
            == Pack 1 (2 cards) ==
              Lightning Bolt
              Sol Ring

            == Pack 2 (1 cards) ==
              Forest
            """.trimIndent()
        )
        assertEquals(listOf("Lightning Bolt", "Sol Ring", "Forest"), entries.map { it.name })
    }

    @Test
    fun `strips a trailing annotation, priced or not`() {
        // How the bot's pack export decorates a card line. An unpriced card
        // has no bracket for the set-tag rule to catch, so the annotation
        // marker has to be handled in its own right.
        assertEquals(
            CardListParser.Entry("Lightning Bolt", 1),
            CardListParser.parse("  Lightning Bolt (\$1.50) — https://img/bolt.jpg").single(),
        )
        assertEquals(
            CardListParser.Entry("Forest", 1),
            CardListParser.parse("  Forest — https://img/forest.jpg").single(),
        )
    }

    @Test
    fun `leaves hyphens and dashes inside a card name alone`() {
        // Only a spaced em dash introduces an annotation.
        assertEquals("Fire-Lit Thicket", CardListParser.parse("Fire-Lit Thicket").single().name)
        assertEquals("Ratchet, Field Medic", CardListParser.parse("Ratchet, Field Medic").single().name)
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
