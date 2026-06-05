package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import common.mtg.CardCategory
import common.mtg.CubeCard
import common.mtg.MtgColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeWebServiceTest {

    private val service = CubeWebService()
    private val mapper = ObjectMapper()

    // --- asFan ---------------------------------------------------------

    @Test
    fun `asFan returns the doc's worked example`() {
        val result = assertInstanceOf(CubeResult.Success::class.java, service.asFan(60, 540, 15))
        assertEquals(1.6667, result.value as Double, 1e-4)
    }

    @Test
    fun `asFan returns a failure for invalid inputs`() {
        val result = assertInstanceOf(CubeResult.Failure::class.java, service.asFan(1, 0, 15))
        assertTrue(result.error.contains("Cube size"))
    }

    // --- parseCards ----------------------------------------------------

    @Test
    fun `parseCards maps name, colour identity, land flag and mana value`() {
        val root = mapper.readTree(
            """
            {"data":[
              {"name":"Lightning Bolt","color_identity":["R"],"type_line":"Instant","cmc":1.0},
              {"name":"Sacred Foundry","color_identity":["R","W"],"type_line":"Land — Mountain Plains","cmc":0.0},
              {"name":"Sol Ring","color_identity":[],"type_line":"Artifact","cmc":1.0}
            ]}
            """.trimIndent()
        )
        val cards = service.parseCards(root)
        assertEquals(3, cards.size)
        assertEquals(setOf(MtgColor.RED), cards[0].colors)
        assertEquals(CardCategory.LAND, cards[1].category)
        assertEquals(CardCategory.COLORLESS, cards[2].category)
    }

    @Test
    fun `parseCards skips entries without a name and tolerates a missing data array`() {
        val root = mapper.readTree("""{"data":[{"type_line":"Instant"},{"name":""}]}""")
        assertTrue(service.parseCards(root).isEmpty())
        assertTrue(service.parseCards(mapper.readTree("""{"object":"error"}""")).isEmpty())
    }

    // --- distribution --------------------------------------------------

    @Test
    fun `distribution returns present categories in colour-pie order with counts and as-fan`() {
        val pool = listOf(
            CubeCard("Bolt", setOf(MtgColor.RED)),
            CubeCard("Shock", setOf(MtgColor.RED)),
            CubeCard("Swords", setOf(MtgColor.WHITE)),
            CubeCard("Wastes", isLand = true),
        )
        val dist = service.distribution(pool, packSize = 4)
        assertEquals(listOf("White", "Red", "Land"), dist.map { it.category })
        val red = dist.first { it.category == "Red" }
        assertEquals(2, red.count)
        // 2 / 4 × 4 = 2.0
        assertEquals(2.0, red.asFan, 1e-9)
    }

    // --- groups (cards per category, with thumbnails) ------------------

    @Test
    fun `groups lists the actual cards per category, deduped, alphabetised, with thumbnails`() {
        val bolt = CubeCard("Bolt", setOf(MtgColor.RED), typeLine = "Instant", manaValue = 1.0)
        val pool = listOf(
            ScryfallCard(CubeCard("Shock", setOf(MtgColor.RED)), "https://img/shock.jpg", "https://img/shock-lg.jpg"),
            ScryfallCard(bolt, "https://img/bolt.jpg", "https://img/bolt-lg.jpg"),
            ScryfallCard(bolt, "https://img/bolt.jpg", "https://img/bolt-lg.jpg"),
            ScryfallCard(CubeCard("Swords", setOf(MtgColor.WHITE)), null, null),
            ScryfallCard(CubeCard("Wastes", isLand = true), null, null),
        )
        val groups = service.groups(pool, packSize = 5)
        assertEquals(listOf("White", "Red", "Land"), groups.map { it.category })

        val red = groups.first { it.category == "Red" }
        assertEquals(listOf("Bolt", "Shock"), red.cards.map { it.name }) // deduped + sorted
        assertEquals("https://img/bolt.jpg", red.cards.first().imageUrl)
        assertEquals("https://img/bolt-lg.jpg", red.cards.first().imageUrlLarge)
        // Stat line fields carry through for the hover preview.
        assertEquals("Instant", red.cards.first().typeLine)
        assertEquals(1.0, red.cards.first().manaValue, 1e-9)
        assertEquals(3, red.count) // count still includes the duplicate
        // 3 red / 5 pool × 5 = 3.0
        assertEquals(3.0, red.asFan, 1e-9)
    }

    // --- parseScryfall (thumbnail extraction) --------------------------

    @Test
    fun `parseScryfall pulls the small thumbnail and the large image for single-faced cards`() {
        val root = mapper.readTree(
            """{"data":[{"name":"Bolt","color_identity":["R"],"type_line":"Instant",
               "image_uris":{"small":"https://img/bolt-small.jpg","normal":"https://img/bolt-normal.jpg"}}]}"""
        )
        val parsed = service.parseScryfall(root)
        assertEquals(1, parsed.size)
        assertEquals("https://img/bolt-small.jpg", parsed.first().imageUrl)
        assertEquals("https://img/bolt-normal.jpg", parsed.first().imageUrlLarge)
    }

    @Test
    fun `parseScryfall falls back to the front face images for double-faced cards`() {
        val root = mapper.readTree(
            """{"data":[{"name":"Delver of Secrets // Insectile Aberration","color_identity":["U"],
               "type_line":"Creature","card_faces":[
                 {"image_uris":{"small":"https://img/delver-front.jpg","normal":"https://img/delver-front-lg.jpg"}},
                 {"image_uris":{"small":"https://img/delver-back.jpg"}}]}]}"""
        )
        val parsed = service.parseScryfall(root).first()
        assertEquals("https://img/delver-front.jpg", parsed.imageUrl)
        assertEquals("https://img/delver-front-lg.jpg", parsed.imageUrlLarge)
    }

    @Test
    fun `parseScryfall yields null images when Scryfall has none`() {
        val root = mapper.readTree("""{"data":[{"name":"Imageless","color_identity":[],"type_line":"Token"}]}""")
        val parsed = service.parseScryfall(root).first()
        assertEquals(null, parsed.imageUrl)
        assertEquals(null, parsed.imageUrlLarge)
    }

    // --- parseList (pasted decklist parsing) ---------------------------

    @Test
    fun `parseList reads one name per line, defaulting to a count of one`() {
        val entries = service.parseList("Lightning Bolt\nForest")
        assertEquals(listOf("Lightning Bolt", "Forest"), entries.map { it.name })
        assertEquals(listOf(1, 1), entries.map { it.count })
    }

    @Test
    fun `parseList honours leading quantities like 3 and 3x`() {
        assertEquals(ListEntry("Forest", 3), service.parseList("3 Forest").single())
        assertEquals(ListEntry("Island", 7), service.parseList("7x Island").single())
        assertEquals(ListEntry("Plains", 2), service.parseList("2X Plains").single())
    }

    @Test
    fun `parseList strips trailing set and collector tags`() {
        assertEquals(ListEntry("Lightning Bolt", 1), service.parseList("1 Lightning Bolt (2X2) 117").single())
        assertEquals(ListEntry("Sol Ring", 1), service.parseList("Sol Ring (CMR)").single())
    }

    @Test
    fun `parseList ignores blank lines and comments`() {
        val entries = service.parseList(
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
    fun `parseList caps an absurd quantity`() {
        // Guards the pool against "99999 Forest" blowing memory.
        assertEquals(100, service.parseList("99999 Forest").single().count)
    }

    @Test
    fun `parseList of blank input is empty`() {
        assertTrue(service.parseList("   \n\n  ").isEmpty())
    }

    // --- preview (pure validation branch) ------------------------------

    @Test
    fun `preview rejects a non-positive pack size without hitting the network`() {
        val result = assertInstanceOf(CubeResult.Failure::class.java, service.preview("set:vow", 0))
        assertTrue(result.error.contains("Pack size"))
    }

    @Test
    fun `preview rejects a blank query without hitting the network`() {
        val result = assertInstanceOf(CubeResult.Failure::class.java, service.preview("   ", 15))
        assertTrue(result.error.contains("Scryfall"))
    }

    @Test
    fun `previewList rejects an empty list without hitting the network`() {
        val result = assertInstanceOf(CubeResult.Failure::class.java, service.previewList("\n  \n", 15))
        assertTrue(result.error.contains("at least one card"))
    }

    @Test
    fun `generateList rejects an empty list without hitting the network`() {
        val result = assertInstanceOf(CubeResult.Failure::class.java, service.generateList("# just a comment", 24, 15, true))
        assertTrue(result.error.contains("at least one card"))
    }
}
