package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CardTypeTest {

    @Test
    fun `classifies the plain types`() {
        assertEquals(CardType.CREATURE, CardType.of("Creature — Goblin"))
        assertEquals(CardType.INSTANT, CardType.of("Instant"))
        assertEquals(CardType.SORCERY, CardType.of("Sorcery"))
        assertEquals(CardType.ENCHANTMENT, CardType.of("Enchantment — Aura"))
        assertEquals(CardType.ARTIFACT, CardType.of("Artifact — Equipment"))
        assertEquals(CardType.PLANESWALKER, CardType.of("Legendary Planeswalker — Jace"))
        assertEquals(CardType.BATTLE, CardType.of("Battle — Siege"))
        assertEquals(CardType.LAND, CardType.of("Land"))
    }

    @Test
    fun `a body wins over its supertypes`() {
        assertEquals(CardType.CREATURE, CardType.of("Artifact Creature — Golem"))
        assertEquals(CardType.CREATURE, CardType.of("Enchantment Creature — God"))
    }

    @Test
    fun `any land is a land, even a land-creature or artifact land`() {
        assertEquals(CardType.LAND, CardType.of("Land Creature — Forest Dryad")) // Dryad Arbor
        assertEquals(CardType.LAND, CardType.of("Artifact Land"))
    }

    @Test
    fun `classifies by the front face of a double-faced card`() {
        // Legion's Landing: front is an enchantment, back is a land.
        assertEquals(CardType.ENCHANTMENT, CardType.of("Legendary Enchantment // Legendary Land"))
        // Westvale Abbey: front is a land.
        assertEquals(CardType.LAND, CardType.of("Land // Creature — Demon"))
    }

    @Test
    fun `enchantment beats artifact when both are present without a body`() {
        assertEquals(CardType.ENCHANTMENT, CardType.of("Artifact Enchantment"))
    }

    @Test
    fun `unknown or empty type lines fall through to Other`() {
        assertEquals(CardType.OTHER, CardType.of(""))
        assertEquals(CardType.OTHER, CardType.of("Tribal — Goblin"))
        assertEquals(CardType.OTHER, CardType.of("Plane — Mirrodin"))
    }
}
