package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CardCombosTest {

    @Test
    fun `comboUrl builds the public Commander Spellbook page`() {
        assertEquals("https://commanderspellbook.com/combo/1234-5678/", CardCombos.comboUrl("1234-5678"))
    }

    @Test
    fun `combos round-trips its fields`() {
        val combos = CardCombos(
            "Thassa's Oracle",
            listOf(CardCombos.Combo("42", listOf("Thassa's Oracle", "Demonic Consultation"), listOf("Win the game"), "u")),
        )
        assertEquals("Thassa's Oracle", combos.cardName)
        assertEquals(1, combos.combos.size)
        assertEquals(listOf("Win the game"), combos.combos.first().produces)
    }
}
