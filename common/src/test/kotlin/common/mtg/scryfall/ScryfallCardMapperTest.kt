package common.mtg.scryfall

import common.mtg.MtgColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the shared card mapping in isolation, through a tiny in-memory
 * [JsonAccessor] so `common` needs no JSON library. The bot's and web's
 * real-JSON parser tests cover their Gson/Jackson adapters end-to-end.
 */
class ScryfallCardMapperTest {

    /** A [JsonAccessor] over nested Kotlin maps/lists, mirroring the tolerant adapter contract. */
    @Suppress("UNCHECKED_CAST")
    private class MapAccessor(private val m: Map<String, Any?>) : JsonAccessor {
        override fun string(key: String) = (m[key] as? String)?.takeIf { it.isNotBlank() }
        override fun double(key: String, default: Double) = (m[key] as? Number)?.toDouble() ?: default
        override fun stringList(key: String) =
            (m[key] as? List<*>)?.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } } ?: emptyList()
        override fun child(key: String) = (m[key] as? Map<String, Any?>)?.let(::MapAccessor)
        override fun children(key: String) =
            (m[key] as? List<*>)?.mapNotNull { (it as? Map<String, Any?>)?.let(::MapAccessor) } ?: emptyList()
    }

    private fun node(vararg pairs: Pair<String, Any?>) = MapAccessor(mapOf(*pairs))

    @Test
    fun `maps a single-faced card's core fields`() {
        val card = ScryfallCardMapper.toCubeCard(
            node(
                "name" to "Lightning Bolt",
                "color_identity" to listOf("R"),
                "type_line" to "Instant",
                "cmc" to 1.0,
                "rarity" to "common",
                "mana_cost" to "{R}",
                "oracle_text" to "Deal 3 damage to any target.",
                "prices" to mapOf("usd" to "1.50", "eur" to "1.20", "tix" to "0.03"),
                "legalities" to mapOf("modern" to "legal", "vintage" to "legal", "standard" to "not_legal"),
            ),
        )!!

        assertEquals("Lightning Bolt", card.name)
        assertEquals(setOf(MtgColor.RED), card.colors)
        assertFalse(card.isLand)
        assertEquals(1.0, card.manaValue)
        assertEquals("common", card.rarity)
        assertEquals("{R}", card.manaCost)
        assertEquals("Deal 3 damage to any target.", card.oracleText)
        assertEquals("1.50", card.priceUsd)
        assertEquals("1.20", card.priceEur)
        assertEquals("0.03", card.priceTix)
        assertEquals(listOf("Modern", "Vintage"), card.legalFormats)
        assertEquals("legal", card.legalities["modern"])
        assertEquals("not_legal", card.legalities["standard"])
    }

    @Test
    fun `returns null without a usable name`() {
        assertNull(ScryfallCardMapper.toCubeCard(node("type_line" to "Instant")))
        assertNull(ScryfallCardMapper.toCubeCard(node("name" to "  ", "type_line" to "Instant")))
    }

    @Test
    fun `detects a land from the front face only`() {
        assertTrue(ScryfallCardMapper.toCubeCard(node("name" to "Forest", "type_line" to "Basic Land — Forest"))!!.isLand)
        // Front face is a spell; the back being a land doesn't make it a land.
        assertFalse(
            ScryfallCardMapper.toCubeCard(
                node("name" to "Legion's Landing", "type_line" to "Legendary Enchantment // Legendary Land"),
            )!!.isLand,
        )
    }

    @Test
    fun `unpriced and unknown fields are null or empty`() {
        val card = ScryfallCardMapper.toCubeCard(node("name" to "Token", "type_line" to "Token"))!!
        assertNull(card.rarity)
        assertNull(card.manaCost)
        assertNull(card.oracleText)
        assertNull(card.priceUsd)
        assertNull(card.imageUrl)
        assertNull(card.imageUrlBack)
        assertTrue(card.legalFormats.isEmpty())
        assertTrue(card.legalities.isEmpty())
    }

    @Test
    fun `a double-faced card pulls mana cost and front image from the first face`() {
        val front = mapOf(
            "name" to "Delver of Secrets",
            "mana_cost" to "{U}",
            "oracle_text" to "Look at the top card.",
            "image_uris" to mapOf("small" to "front-small.jpg", "normal" to "front.jpg"),
        )
        val back = mapOf(
            "name" to "Insectile Aberration",
            "oracle_text" to "Flying.",
            "image_uris" to mapOf("normal" to "back.jpg"),
        )
        val node = node(
            "name" to "Delver of Secrets // Insectile Aberration",
            "type_line" to "Creature — Human Wizard // Creature — Human Insect",
            "card_faces" to listOf(front, back),
        )

        val card = ScryfallCardMapper.toCubeCard(node)!!
        assertEquals("{U}", card.manaCost)
        assertEquals("back.jpg", card.imageUrlBack)
        assertEquals("front-small.jpg", ScryfallCardMapper.frontImageUrl(node, "small"))
        assertEquals("front.jpg", ScryfallCardMapper.frontImageUrl(node, "normal"))
    }

    @Test
    fun `oracle text combines both faces, decorating face names per surface`() {
        val node = node(
            "name" to "A // B",
            "card_faces" to listOf(
                mapOf("name" to "A", "oracle_text" to "Do A."),
                mapOf("name" to "B", "oracle_text" to "Do B."),
            ),
        )
        // Default: plain face names (the web).
        assertEquals("A\nDo A.\n\nB\nDo B.", ScryfallCardMapper.toCubeCard(node)!!.oracleText)
        // Discord: face names bolded with markdown.
        assertEquals(
            "**A**\nDo A.\n\n**B**\nDo B.",
            ScryfallCardMapper.toCubeCard(node, decorateFaceName = { "**$it**" })!!.oracleText,
        )
    }

    @Test
    fun `a split card with one shared image has no back image`() {
        // Split / adventure cards have two faces but no per-face image_uris.
        val node = node(
            "name" to "Fire // Ice",
            "card_faces" to listOf(
                mapOf("name" to "Fire", "oracle_text" to "Deal 2 damage."),
                mapOf("name" to "Ice", "oracle_text" to "Tap target."),
            ),
        )
        assertNull(ScryfallCardMapper.backImageUrl(node))
    }

    @Test
    fun `a face with no rules text is skipped, not rendered blank`() {
        // Meld backs and some tokens carry a face with no oracle_text.
        val node = node(
            "name" to "A // B",
            "card_faces" to listOf(
                mapOf("name" to "A", "oracle_text" to "Do A."),
                mapOf("name" to "B"),
            ),
        )

        assertEquals("A\nDo A.", ScryfallCardMapper.toCubeCard(node)!!.oracleText)
    }

    @Test
    fun `a nameless face keeps its text undecorated`() {
        val node = node(
            "name" to "A // B",
            "card_faces" to listOf(mapOf("oracle_text" to "Do A."), mapOf("name" to "B", "oracle_text" to "Do B.")),
        )

        assertEquals(
            "Do A.\n\n**B**\nDo B.",
            ScryfallCardMapper.toCubeCard(node, decorateFaceName = { "**$it**" })!!.oracleText,
        )
    }

    @Test
    fun `faces with no text at all leave the card textless`() {
        val node = node(
            "name" to "A // B",
            "card_faces" to listOf(mapOf("name" to "A"), mapOf("name" to "B")),
        )

        assertNull(ScryfallCardMapper.toCubeCard(node)!!.oracleText) { "empty text should be absent, not an empty string" }
    }

    @Test
    fun `a card with no images anywhere has none`() {
        val node = node("name" to "Bolt", "type_line" to "Instant")

        assertNull(ScryfallCardMapper.frontImageUrl(node, "small"))
        assertNull(ScryfallCardMapper.backImageUrl(node))
    }

    @Test
    fun `a size Scryfall didn't send is null rather than a wrong image`() {
        val node = node("name" to "Bolt", "image_uris" to mapOf("small" to "s.jpg"))

        assertEquals("s.jpg", ScryfallCardMapper.frontImageUrl(node, "small"))
        assertNull(ScryfallCardMapper.frontImageUrl(node, "png"))
    }

    @Test
    fun `a single-face list has no back image`() {
        // Adventure cards are sometimes shaped this way in older payloads.
        val node = node(
            "name" to "Bolt",
            "card_faces" to listOf(mapOf("name" to "Bolt", "image_uris" to mapOf("normal" to "front.jpg"))),
        )

        assertEquals("front.jpg", ScryfallCardMapper.frontImageUrl(node, "normal"))
        assertNull(ScryfallCardMapper.backImageUrl(node)) { "one face means there's no back to show" }
    }

    @Test
    fun `a face with no mana cost of its own leaves the card costless`() {
        val node = node("name" to "Land", "card_faces" to listOf(mapOf("name" to "Land")))

        assertNull(ScryfallCardMapper.toCubeCard(node)!!.manaCost)
    }

    @Test
    fun `the front face's cost wins over a back face that has one`() {
        val node = node(
            "name" to "A // B",
            "card_faces" to listOf(
                mapOf("name" to "A", "mana_cost" to "{U}"),
                mapOf("name" to "B", "mana_cost" to "{2}{R}"),
            ),
        )

        assertEquals("{U}", ScryfallCardMapper.toCubeCard(node)!!.manaCost) { "you cast the front face" }
    }

    @Test
    fun `colours come back in canonical WUBRG order regardless of input order`() {
        val card = ScryfallCardMapper.toCubeCard(
            node("name" to "Niv-Mizzet", "type_line" to "Creature", "color_identity" to listOf("R", "U")),
        )!!
        assertEquals(setOf(MtgColor.BLUE, MtgColor.RED), card.colors)
    }
}
