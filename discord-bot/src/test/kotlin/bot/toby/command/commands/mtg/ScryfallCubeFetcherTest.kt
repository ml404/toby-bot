package bot.toby.command.commands.mtg

import com.google.gson.JsonParser
import common.mtg.CardCategory
import common.mtg.MtgColor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ScryfallCubeFetcherTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Builds a fetcher whose ktor client is driven by [engine]; runs on Unconfined for fast tests. */
    private fun fetcherWith(engine: MockEngine): ScryfallCubeFetcher =
        ScryfallCubeFetcher(HttpClient(engine) { install(HttpTimeout) }, Dispatchers.Unconfined)

    /** A fetcher with a never-called engine — for the pure [ScryfallCubeFetcher.parseCard] tests. */
    private val fetcher = fetcherWith(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })

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
    fun `parseCard buckets a modal land-back card by its front face, not as a land`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Legion's Landing // Adanto, the First Fort","color_identity":["W"],
               "type_line":"Legendary Enchantment // Legendary Land"}""")
        )!!
        assertEquals(false, card.isLand)
        assertEquals(CardCategory.WHITE, card.category)
    }

    @Test
    fun `parseCard keeps a front-face land as a land`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Westvale Abbey // Ormendahl, Profane Prince","color_identity":[],
               "type_line":"Land // Creature — Demon"}""")
        )!!
        assertEquals(CardCategory.LAND, card.category)
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
    fun `parseCard reads the rarity, leaving it null when absent`() {
        val withRarity = fetcher.parseCard(
            obj("""{"name":"Ragavan","color_identity":["R"],"type_line":"Legendary Creature","rarity":"mythic"}""")
        )!!
        assertEquals("mythic", withRarity.rarity)
        val without = fetcher.parseCard(obj("""{"name":"Token","color_identity":[],"type_line":"Token"}"""))!!
        assertEquals(null, without.rarity)
    }

    @Test
    fun `parseCard reads the mana cost, single-faced and from the front of a DFC`() {
        val single = fetcher.parseCard(
            obj("""{"name":"Bolt","color_identity":["R"],"type_line":"Instant","mana_cost":"{R}"}""")
        )!!
        assertEquals("{R}", single.manaCost)
        val dfc = fetcher.parseCard(
            obj("""{"name":"Huntmaster // Ravager","color_identity":["R","G"],"type_line":"Creature","mana_cost":"",
               "card_faces":[{"mana_cost":"{2}{R}{G}"},{"mana_cost":""}]}""")
        )!!
        assertEquals("{2}{R}{G}", dfc.manaCost)
    }

    @Test
    fun `parseCard reads oracle text and the back-face image`() {
        val single = fetcher.parseCard(
            obj("""{"name":"Bolt","color_identity":["R"],"type_line":"Instant","oracle_text":"Deals 3 damage."}""")
        )!!
        assertEquals("Deals 3 damage.", single.oracleText)
        assertEquals(null, single.imageUrlBack)

        val dfc = fetcher.parseCard(
            obj("""{"name":"Delver // Aberration","color_identity":["U"],"type_line":"Creature","oracle_text":"",
               "card_faces":[
                 {"name":"Delver of Secrets","oracle_text":"Look at the top card.","image_uris":{"normal":"front.jpg"}},
                 {"name":"Insectile Aberration","oracle_text":"Flying.","image_uris":{"normal":"back.jpg"}}]}""")
        )!!
        // Both faces' text combined, each headed by its face name.
        assertTrue(dfc.oracleText!!.contains("Look at the top card."))
        assertTrue(dfc.oracleText!!.contains("Insectile Aberration"))
        assertEquals("back.jpg", dfc.imageUrlBack)
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

    // --- helpers -------------------------------------------------------

    private fun page(vararg cards: String, hasMore: Boolean = false, nextPage: String? = null): String {
        val data = cards.joinToString(",")
        val tail = if (hasMore) ""","has_more":true,"next_page":"${nextPage}"""" else ""","has_more":false"""
        return """{"data":[$data]$tail}"""
    }

    // --- fetchByNames (saved-cube resolution via /cards/collection) ----

    @Test
    fun `fetchByNames resolves a list of names into cards`() = runBlocking {
        val fetcher = fetcherWith(
            MockEngine {
                respond(
                    """{"data":[
                        {"name":"Lightning Bolt","color_identity":["R"],"type_line":"Instant","cmc":1},
                        {"name":"Forest","color_identity":[],"type_line":"Basic Land — Forest"}
                    ]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        )
        val result = fetcher.fetchByNames(listOf("Lightning Bolt", "Forest"))
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(setOf("Lightning Bolt", "Forest"), success.cards.map { it.name }.toSet())
    }

    @Test
    fun `fetchByNames fails fast on an empty list without calling the network`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        val result = fetcherWith(engine).fetchByNames(listOf("  ", ""))
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `fetchByNames reports an HTTP error`() = runBlocking {
        val result = fetcherWith(MockEngine { respondError(HttpStatusCode.InternalServerError) })
            .fetchByNames(listOf("Bolt"))
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("HTTP 500"))
    }

    @Test
    fun `fetchByNames caps how many names it looks up`() = runBlocking {
        // 800 distinct names → capped at 750 → ceil(750/75) = 10 batches,
        // not ceil(800/75) = 11. Guards against spamming Scryfall.
        val engine = MockEngine { respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders) }
        val result = fetcherWith(engine).fetchByNames((1..800).map { "Card $it" })
        assertEquals(10, engine.requestHistory.size)
        // All ten POSTs returned no cards, so nothing resolved.
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
    }

    @Test
    fun `fetchByNames flags a list capped past the ceiling`() = runBlocking {
        // 760 names → over the 750 ceiling → capped, and at least one resolves.
        val engine = MockEngine {
            respond(
                """{"data":[{"name":"Card 1","color_identity":["R"],"type_line":"Instant"}]}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val result = fetcherWith(engine).fetchByNames((1..760).map { "Card $it" })
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertTrue(success.capped, "a 760-name list should be flagged as capped")
    }

    @Test
    fun `fetchByNames fails when nothing resolves`() = runBlocking {
        val result = fetcherWith(MockEngine { respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders) })
            .fetchByNames(listOf("Definitely Not A Card"))
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
    }

    @Test
    fun `fetchByNames posts to the collection endpoint`() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"data":[{"name":"Bolt","color_identity":["R"],"type_line":"Instant"}]}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        fetcherWith(engine).fetchByNames(listOf("Bolt"))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.toString().endsWith("/cards/collection"))
    }

    // --- fetchNamed (fuzzy single-card for [[mentions]]) ----------------

    @Test
    fun `fetchNamed resolves a single fuzzy name to a card`() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"name":"Lightning Bolt","color_identity":["R"],"type_line":"Instant","cmc":1,
                   "image_uris":{"normal":"https://img/bolt.jpg"}}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val card = fetcherWith(engine).fetchNamed("lightning bolt")
        assertEquals("Lightning Bolt", card?.name)
        assertEquals("https://img/bolt.jpg", card?.imageUrl)
        assertTrue(engine.requestHistory.single().url.toString().contains("/cards/named?fuzzy="))
    }

    @Test
    fun `fetchNamed returns null when nothing matches (404)`() = runBlocking {
        assertNull(fetcherWith(MockEngine { respondError(HttpStatusCode.NotFound) }).fetchNamed("zzzznotacard"))
    }

    @Test
    fun `fetchNamed returns null for a blank name without calling the network`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        assertNull(fetcherWith(engine).fetchNamed("   "))
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `fetchNamed swallows a transport failure as null`() = runBlocking {
        assertNull(fetcherWith(MockEngine { throw IOException("down") }).fetchNamed("Bolt"))
    }

    // --- fetch ---------------------------------------------------------

    @Test
    fun `fetch returns parsed cards on a single page`() = runBlocking {
        val fetcher = fetcherWith(
            MockEngine {
                respond(
                    page(
                        """{"name":"Bolt","color_identity":["R"],"type_line":"Instant","cmc":1}""",
                        """{"name":"Forest","color_identity":[],"type_line":"Basic Land — Forest"}""",
                    ),
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        )
        val result = fetcher.fetch("t:instant", maxCards = 100)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(2, success.cards.size)
        assertEquals(listOf("Bolt", "Forest"), success.cards.map { it.name })
        assertEquals(false, success.capped)
    }

    @Test
    fun `fetch follows pagination until has_more is false`() = runBlocking {
        val fetcher = fetcherWith(
            MockEngine { request ->
                if (request.url.toString().contains("page=2")) {
                    respond(page("""{"name":"B","color_identity":["G"],"type_line":"Creature"}"""), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(
                        page(
                            """{"name":"A","color_identity":["U"],"type_line":"Creature"}""",
                            hasMore = true, nextPage = "https://api.scryfall.com/cards/search?page=2",
                        ),
                        HttpStatusCode.OK, jsonHeaders,
                    )
                }
            }
        )
        val result = fetcher.fetch("t:creature", maxCards = 100)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(listOf("A", "B"), success.cards.map { it.name })
    }

    @Test
    fun `fetch caps the result at maxCards and flags it`() = runBlocking {
        val fetcher = fetcherWith(
            MockEngine {
                respond(
                    page(
                        """{"name":"A","color_identity":[],"type_line":"X"}""",
                        """{"name":"B","color_identity":[],"type_line":"X"}""",
                        """{"name":"C","color_identity":[],"type_line":"X"}""",
                    ),
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        )
        val result = fetcher.fetch("anything", maxCards = 2)
        val success = assertInstanceOf(ScryfallCubeFetcher.Result.Success::class.java, result)
        assertEquals(2, success.cards.size)
        assertTrue(success.capped, "overshooting maxCards should flag the pool as capped")
    }

    @Test
    fun `fetch maps a 404 to a friendly no-matches failure`() = runBlocking {
        val result = fetcherWith(MockEngine { respondError(HttpStatusCode.NotFound) }).fetch("set:nonsense", maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("No cards matched"))
    }

    @Test
    fun `fetch reports other HTTP errors`() = runBlocking {
        val result = fetcherWith(MockEngine { respondError(HttpStatusCode.InternalServerError) }).fetch("t:goblin", maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("HTTP 500"))
    }

    @Test
    fun `fetch rejects a blank query without touching the network`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        val result = fetcherWith(engine).fetch("   ", maxCards = 10)
        assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `fetch fails when the page has no usable cards`() = runBlocking {
        val result = fetcherWith(MockEngine { respond(page(), HttpStatusCode.OK, jsonHeaders) }).fetch("t:nothing", maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("No usable cards"))
    }

    @Test
    fun `fetch surfaces transport exceptions as a failure`() = runBlocking {
        val result = fetcherWith(MockEngine { throw IOException("network down") }).fetch("t:elf", maxCards = 10)
        val failure = assertInstanceOf(ScryfallCubeFetcher.Result.Failure::class.java, result)
        assertTrue(failure.message.contains("Couldn't reach Scryfall"))
    }
}
