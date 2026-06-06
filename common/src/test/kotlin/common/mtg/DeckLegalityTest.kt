package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeckLegalityTest {

    private fun card(name: String, vararg legalities: Pair<String, String>) =
        CubeCard(name = name, legalities = legalities.toMap())

    @Test
    fun `statusOf maps the raw Scryfall strings`() {
        assertEquals(DeckLegality.Status.LEGAL, DeckLegality.statusOf("legal"))
        assertEquals(DeckLegality.Status.NOT_LEGAL, DeckLegality.statusOf("not_legal"))
        assertEquals(DeckLegality.Status.BANNED, DeckLegality.statusOf("banned"))
        assertEquals(DeckLegality.Status.RESTRICTED, DeckLegality.statusOf("restricted"))
        assertEquals(DeckLegality.Status.UNKNOWN, DeckLegality.statusOf(null))
        assertEquals(DeckLegality.Status.UNKNOWN, DeckLegality.statusOf("weird"))
    }

    @Test
    fun `a deck with only legal cards is legal`() {
        val deck = listOf(
            card("Lightning Bolt", "modern" to "legal"),
            card("Mountain", "modern" to "legal"),
        )
        val report = DeckLegality.check(deck, "modern")
        assertTrue(report.legal)
        assertEquals(2, report.total)
        assertTrue(report.banned.isEmpty())
        assertTrue(report.notLegal.isEmpty())
    }

    @Test
    fun `banned and not-in-format cards make the deck illegal and are bucketed`() {
        val deck = listOf(
            card("Lightning Bolt", "modern" to "legal"),
            card("Lurrus of the Dream-Den", "modern" to "banned"),
            card("Black Lotus", "modern" to "not_legal"),
        )
        val report = DeckLegality.check(deck, "modern")
        assertFalse(report.legal)
        assertEquals(listOf("Lurrus of the Dream-Den"), report.banned)
        assertEquals(listOf("Black Lotus"), report.notLegal)
    }

    @Test
    fun `restricted cards are flagged but do not fail the deck`() {
        val deck = listOf(
            card("Sol Ring", "vintage" to "restricted"),
            card("Island", "vintage" to "legal"),
        )
        val report = DeckLegality.check(deck, "vintage")
        assertTrue(report.legal)
        assertEquals(listOf("Sol Ring"), report.restricted)
    }

    @Test
    fun `duplicate offenders are reported once, in first-seen order`() {
        val deck = listOf(
            card("Lurrus of the Dream-Den", "modern" to "banned"),
            card("Lurrus of the Dream-Den", "modern" to "banned"),
            card("Oko, Thief of Crowns", "modern" to "banned"),
        )
        val report = DeckLegality.check(deck, "modern")
        assertEquals(listOf("Lurrus of the Dream-Den", "Oko, Thief of Crowns"), report.banned)
    }

    @Test
    fun `a card missing the format's status counts as unknown, not legal-failing`() {
        val deck = listOf(card("Mystery Card")) // no legalities at all
        val report = DeckLegality.check(deck, "modern")
        assertTrue(report.legal) // unknown doesn't fail the deck
        assertEquals(listOf("Mystery Card"), report.unknown)
    }
}
