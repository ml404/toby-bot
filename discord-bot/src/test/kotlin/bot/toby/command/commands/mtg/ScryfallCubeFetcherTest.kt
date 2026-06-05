package bot.toby.command.commands.mtg

import com.google.gson.JsonParser
import common.mtg.CardCategory
import common.mtg.MtgColor
import io.mockk.every
import io.mockk.mockk
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.StatusLine
import org.apache.http.client.HttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ScryfallCubeFetcherTest {

    private val fetcher = ScryfallCubeFetcher()

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    // --- parseCard -----------------------------------------------------

    @Test
    fun `parseCard maps name, colour identity, mana value and type`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Lightning Bolt","color_identity":["R"],"type_line":"Instant","cmc":1.0}""")
        )!!
        assertEquals("Lightning Bolt", card.name)
        assertEquals(setOf(MtgColor.RED), card.colors)
        assertEquals(1.0, card.manaValue)
        assertEquals(false, card.isLand)
        assertEquals(CardCategory.RED, card.category)
    }

    @Test
    fun `parseCard pulls the normal image link for a single-faced card`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Bolt","color_identity":["R"],"type_line":"Instant",
               "image_uris":{"small":"s.jpg","normal":"https://img/bolt.jpg"}}""")
        )!!
        assertEquals("https://img/bolt.jpg", card.imageUrl)
    }

    @Test
    fun `parseCard falls back to the front face image for a double-faced card`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Delver // Aberration","color_identity":["U"],"type_line":"Creature",
               "card_faces":[{"image_uris":{"normal":"https://img/front.jpg"}},
                             {"image_uris":{"normal":"https://img/back.jpg"}}]}""")
        )!!
        assertEquals("https://img/front.jpg", card.imageUrl)
    }

    @Test
    fun `parseCard leaves the image null when Scryfall has none`() {
        val card = fetcher.parseCard(obj("""{"name":"Token","color_identity":[],"type_line":"Token"}"""))!!
        assertEquals(null, card.imageUrl)
    }

    @Test
    fun `parseCard flags lands from the type line`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Sacred Foundry","color_identity":["R","W"],"type_line":"Land — Mountain Plains"}""")
        )!!
        assertTrue(card.isLand)
        assertEquals(CardCategory.LAND, card.category)
    }

    @Test
    fun `parseCard treats a card with no colour identity as colourless`() {
        val card = fetcher.parseCard(obj("""{"name":"Sol Ring","color_identity":[],"type_line":"Artifact"}"""))!!
        assertEquals(CardCategory.COLORLESS, card.category)
        assertEquals(0.0, card.manaValue, "missing cmc defaults to 0")
    }

    @Test
    fun `parseCard returns null when there is no usable name`() {
        assertNull(fetcher.parseCard(obj("""{"type_line":"Instant"}""")))
        assertNull(fetcher.parseCard(obj("""{"name":"","type_line":"Instant"}""")))
    }

    // --- fetch ---------------------------------------------------------

    private fun page(vararg cards: String, hasMore: Boolean = false, nextPage: String? = null): String {
        val data = cards.joinToString(",")
        val tail = if (hasMore) ""","has_more":true,"next_page":"${nextPage}"""" else ""","has_more":false"""
        return """{"data":[$data]$tail}"""
    }

    private fun stubResponse(client: HttpClient, status: Int, body: String) {
        val response = mockk<HttpResponse>()
        val statusLine = mockk<StatusLine>()
        val entity = mockk<HttpEntity>(relaxed = true)
        every { client.execute(any()) } returns response
        every { response.statusLine } returns statusLine
        every { statusLine.statusCode } returns status
        every { response.entity } returns entity
        every { entity.content } returns ByteArrayInputStream(body.toByteArray())
    }

    // --- fetchByNames (saved-cube resolution via /cards/collection) ----

    @Test
    fun `fetchByNames resolves a list of names into cards`() {
        val client = mockk<HttpClient>()
        stubResponse(
            client, 200,
            """{"data":[
                {"name":"Lightning Bolt","color_identity":["R"],"type_line":"Instant","cmc":1},
                {"name":"Forest","color_identity":[],"type_line":"Basic Land — Forest"}
            ]}""",
        )
        val result = fetcher.fetchByNames(listOf("Lightning Bolt", "Forest"), client)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(listOf("Lightning Bolt", "Forest"), success.cards.map { it.name })
    }

    @Test
    fun `fetchByNames fails fast on an empty list without calling the network`() {
        val client = mockk<HttpClient>()
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, fetcher.fetchByNames(listOf("  ", ""), client))
    }

    @Test
    fun `fetchByNames reports an HTTP error`() {
        val client = mockk<HttpClient>()
        stubResponse(client, 500, "boom")
        val result = fetcher.fetchByNames(listOf("Bolt"), client)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("HTTP 500"))
    }

    @Test
    fun `fetchByNames fails when nothing resolves`() {
        val client = mockk<HttpClient>()
        stubResponse(client, 200, """{"data":[]}""")
        val result = fetcher.fetchByNames(listOf("Definitely Not A Card"), client)
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
    }

    @Test
    fun `fetch returns parsed cards on a single page`() {
        val client = mockk<HttpClient>()
        stubResponse(
            client, 200,
            page(
                """{"name":"Bolt","color_identity":["R"],"type_line":"Instant","cmc":1}""",
                """{"name":"Forest","color_identity":[],"type_line":"Basic Land — Forest"}""",
            ),
        )
        val result = fetcher.fetch("t:instant", maxCards = 100, httpClient = client)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(2, success.cards.size)
        assertEquals(listOf("Bolt", "Forest"), success.cards.map { it.name })
    }

    @Test
    fun `fetch follows pagination until has_more is false`() {
        val client = mockk<HttpClient>()
        val response = mockk<HttpResponse>()
        val statusLine = mockk<StatusLine>()
        val entity = mockk<HttpEntity>(relaxed = true)
        every { client.execute(any()) } returns response
        every { response.statusLine } returns statusLine
        every { statusLine.statusCode } returns 200
        every { response.entity } returns entity
        every { entity.content } returnsMany listOf(
            ByteArrayInputStream(
                page("""{"name":"A","color_identity":["U"],"type_line":"Creature"}""", hasMore = true, nextPage = "https://api.scryfall.com/next")
                    .toByteArray()
            ),
            ByteArrayInputStream(
                page("""{"name":"B","color_identity":["G"],"type_line":"Creature"}""").toByteArray()
            ),
        )
        val result = fetcher.fetch("t:creature", maxCards = 100, httpClient = client)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(listOf("A", "B"), success.cards.map { it.name })
    }

    @Test
    fun `fetch caps the result at maxCards`() {
        val client = mockk<HttpClient>()
        stubResponse(
            client, 200,
            page(
                """{"name":"A","color_identity":[],"type_line":"X"}""",
                """{"name":"B","color_identity":[],"type_line":"X"}""",
                """{"name":"C","color_identity":[],"type_line":"X"}""",
            ),
        )
        val result = fetcher.fetch("anything", maxCards = 2, httpClient = client)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(2, success.cards.size)
    }

    @Test
    fun `fetch maps a 404 to a friendly no-matches failure`() {
        val client = mockk<HttpClient>()
        stubResponse(client, 404, """{"object":"error"}""")
        val result = fetcher.fetch("set:nonsense", httpClient = client, maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("No cards matched"))
    }

    @Test
    fun `fetch reports other HTTP errors`() {
        val client = mockk<HttpClient>()
        stubResponse(client, 500, "boom")
        val result = fetcher.fetch("t:goblin", httpClient = client, maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("HTTP 500"))
    }

    @Test
    fun `fetch rejects a blank query without touching the network`() {
        val client = mockk<HttpClient>()
        val result = fetcher.fetch("   ", httpClient = client, maxCards = 10)
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
    }

    @Test
    fun `fetch fails when the page has no usable cards`() {
        val client = mockk<HttpClient>()
        stubResponse(client, 200, page())
        val result = fetcher.fetch("t:nothing", httpClient = client, maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("No usable cards"))
    }

    @Test
    fun `fetch surfaces transport exceptions as a failure`() {
        val client = mockk<HttpClient>()
        every { client.execute(any()) } throws java.io.IOException("network down")
        val result = fetcher.fetch("t:elf", httpClient = client, maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("Couldn't reach Scryfall"))
    }
}
