package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackListWriterTest {

    @Test
    fun `header pluralises the card count`() {
        assertEquals("== Pack 1 (15 cards) ==", PackListWriter.header(1, 15))
        assertEquals("== Pack 2 (1 card) ==", PackListWriter.header(2, 1))
    }

    @Test
    fun `header carries an annotation between the count and the closing marker`() {
        assertEquals("== Pack 1 (2 cards) — ≈ \$4.20 ==", PackListWriter.header(1, 2, " — ≈ \$4.20"))
    }

    @Test
    fun `provenance says where a deal came from, its shape and when`() {
        assertEquals(
            "# cube:vintage — 24 packs of 15 — 2026-08-09",
            PackListWriter.provenance(24, 15, "cube:vintage", "2026-08-09"),
        )
        // Either part may be missing; the shape rides along with whatever's left.
        assertEquals("# 8 packs of 15 — 2026-08-09", PackListWriter.provenance(8, 15, "  ", "2026-08-09"))
        assertEquals("# cube:vintage — 8 packs of 15", PackListWriter.provenance(8, 15, "cube:vintage"))
        // Nothing to say about where it came from — no line, not an empty one.
        assertNull(PackListWriter.provenance(1, 15))
    }

    @Test
    fun `a written provenance line is a comment the parser ignores`() {
        val text = PackListWriter.write(
            listOf(listOf(CubeCard("Lightning Bolt", setOf(MtgColor.RED)))),
            source = "cube:vintage",
            dealtOn = "2026-08-09",
        )

        assertTrue(text.startsWith("# cube:vintage")) { "expected the provenance line first, got:\n${text.take(80)}" }
        assertEquals("# cube:vintage — 1 pack of 1 — 2026-08-09", PackListParser.provenanceOf(text))
        assertEquals(listOf("Lightning Bolt"), PackListParser.parse(text).single().entries.map { it.name })
    }

    @Test
    fun `write lays packs out the way the parser reads them`() {
        val packs = listOf(
            listOf(CubeCard("Lightning Bolt", setOf(MtgColor.RED)), CubeCard("Forest", isLand = true)),
            listOf(CubeCard("Sol Ring")),
        )

        val text = PackListWriter.write(packs)

        assertEquals(
            """
            == Pack 1 (2 cards) ==
              Lightning Bolt
              Forest

            == Pack 2 (1 card) ==
              Sol Ring

            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun `annotated output still parses back to the same packs`() {
        val packs = listOf(listOf(CubeCard("Lightning Bolt", setOf(MtgColor.RED)), CubeCard("Forest", isLand = true)))

        val text = PackListWriter.write(
            packs,
            packSuffix = { "${PackListWriter.ANNOTATION_SEPARATOR}≈ \$2.10" },
            annotate = { card -> if (card.isLand) "" else " (\$2.00)${PackListWriter.ANNOTATION_SEPARATOR}https://img/bolt.jpg" },
        )

        assertEquals(
            listOf("Lightning Bolt", "Forest"),
            PackListParser.parse(text).single().entries.map { it.name },
        )
    }
}
