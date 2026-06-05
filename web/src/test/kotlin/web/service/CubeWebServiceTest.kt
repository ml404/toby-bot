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
}
