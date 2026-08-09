package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackListParserTest {

    /** Exactly what the Generate tab's download button writes. */
    private fun downloaded(vararg packs: List<String>): String =
        packs.mapIndexed { i, pack ->
            "== Pack ${i + 1} (${pack.size} cards) ==\n" + pack.joinToString("\n") { "  $it" }
        }.joinToString("\n\n") + "\n"

    @Test
    fun `reads a downloaded pack list back into its packs`() {
        val text = downloaded(
            listOf("Lightning Bolt", "Sol Ring"),
            listOf("Forest", "Llanowar Elves", "Giant Growth"),
        )

        val packs = PackListParser.parse(text)

        assertEquals(2, packs.size)
        assertEquals(listOf("Lightning Bolt", "Sol Ring"), packs[0].entries.map { it.name })
        assertEquals(listOf("Forest", "Llanowar Elves", "Giant Growth"), packs[1].entries.map { it.name })
        assertEquals("Pack 1 (2 cards)", packs[0].label)
    }

    @Test
    fun `keeps file order and repeated copies rather than collapsing them`() {
        // A deal is a sequence, not a set: two Forests in one pack are two
        // slots, and the order is the order they were opened in.
        val packs = PackListParser.parse("== Pack 1 ==\n  Forest\n  Sol Ring\n  Forest\n")

        assertEquals(listOf("Forest", "Sol Ring", "Forest"), packs.single().entries.map { it.name })
    }

    @Test
    fun `text with no dividers is one pack`() {
        val packs = PackListParser.parse("Lightning Bolt\nSol Ring\n")

        assertEquals(1, packs.size)
        assertNull(packs.single().label) { "a divider-less block has no header to label it" }
        assertEquals(listOf("Lightning Bolt", "Sol Ring"), packs.single().entries.map { it.name })
    }

    @Test
    fun `honours quantities, set tags and comments inside a pack`() {
        val packs = PackListParser.parse(
            """
            == Pack 1 (3 cards) ==
            # opened first
            2 Forest
            1 Lightning Bolt (2X2) 117
            """.trimIndent()
        )

        assertEquals(listOf(CardListParser.Entry("Forest", 2), CardListParser.Entry("Lightning Bolt", 1)), packs.single().entries)
    }

    @Test
    fun `drops empty packs and empty input`() {
        assertTrue(PackListParser.parse("").isEmpty())
        assertTrue(PackListParser.parse("== Pack 1 (0 cards) ==\n\n== Pack 2 (0 cards) ==\n").isEmpty())
        // A header with nothing under it doesn't shift the following pack's number.
        val packs = PackListParser.parse("== Pack 1 ==\n\n== Pack 2 ==\n  Sol Ring\n")
        assertEquals(listOf("Sol Ring"), packs.single().entries.map { it.name })
    }

    @Test
    fun `provenanceOf reads the leading comment, if there is one`() {
        val text = "# cube:vintage — 2 packs of 1 — 2026-08-09\n\n== Pack 1 (1 card) ==\n  Sol Ring\n"

        assertEquals("# cube:vintage — 2 packs of 1 — 2026-08-09", PackListParser.provenanceOf(text))
        assertNull(PackListParser.provenanceOf("== Pack 1 (1 card) ==\n  Sol Ring\n"))
        assertNull(PackListParser.provenanceOf(""))
    }

    @Test
    fun `provenanceOf ignores a comment that isn't the preamble`() {
        // A note someone added next to a card is theirs, not the deal's.
        val text = "== Pack 1 (1 card) ==\n  Sol Ring\n# my favourite pack\n"

        assertNull(PackListParser.provenanceOf(text))
    }

    @Test
    fun `round-trips a full 24-pack deal`() {
        val deal = (1..24).map { p -> (1..15).map { c -> "Card $p-$c" } }

        val packs = PackListParser.parse(downloaded(*deal.toTypedArray()))

        assertEquals(24, packs.size)
        assertTrue(packs.all { it.entries.size == 15 })
        assertEquals(deal, packs.map { pack -> pack.entries.map { it.name } })
    }
}
