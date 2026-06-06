package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RarityTest {

    @Test
    fun `maps the standard Scryfall rarities, case-insensitively`() {
        assertEquals(Rarity.COMMON, Rarity.parse("common"))
        assertEquals(Rarity.UNCOMMON, Rarity.parse("Uncommon"))
        assertEquals(Rarity.RARE, Rarity.parse("RARE"))
        assertEquals(Rarity.MYTHIC, Rarity.parse(" mythic "))
    }

    @Test
    fun `special, bonus, unknown and null all fold into Other`() {
        assertEquals(Rarity.OTHER, Rarity.parse("special"))
        assertEquals(Rarity.OTHER, Rarity.parse("bonus"))
        assertEquals(Rarity.OTHER, Rarity.parse("legendary"))
        assertEquals(Rarity.OTHER, Rarity.parse(""))
        assertEquals(Rarity.OTHER, Rarity.parse(null))
    }
}
