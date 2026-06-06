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
    fun `parseCard reads prices, treating a JSON null or absent price as null`() {
        val priced = fetcher.parseCard(
            obj("""{"name":"Ragavan","color_identity":["R"],"type_line":"Legendary Creature",
               "prices":{"usd":"60.00","eur":null,"tix":"12.00"}}""")
        )!!
        assertEquals("60.00", priced.priceUsd)
        assertEquals(null, priced.priceEur) // a JSON null price → null, not "null"
        assertEquals("12.00", priced.priceTix)

        val unpriced = fetcher.parseCard(obj("""{"name":"Token","color_identity":[],"type_line":"Token"}"""))!!
        assertEquals(null, unpriced.priceUsd)
        assertEquals(null, unpriced.priceEur)
        assertEquals(null, unpriced.priceTix)
    }

    @Test
    fun `parseCard reads the legal formats from Scryfall legalities, in FORMATS order`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Ragavan","color_identity":["R"],"type_line":"Legendary Creature",
               "legalities":{"standard":"not_legal","pioneer":"not_legal","modern":"legal","legacy":"legal",
                 "vintage":"legal","pauper":"not_legal","commander":"legal"}}""")
        )!!
        assertEquals(listOf("Modern", "Legacy", "Vintage", "Commander"), card.legalFormats)
        // The full raw status map drives the deck-legality checker.
        assertEquals("not_legal", card.legalities["standard"])
        assertEquals("legal", card.legalities["modern"])

        val noLegalities = fetcher.parseCard(obj("""{"name":"Token","color_identity":[],"type_line":"Token"}"""))!!
        assertTrue(noLegalities.legalFormats.isEmpty())
        assertTrue(noLegalities.legalities.isEmpty())
    }

    @Test
    fun `parseCard treats a banned status as kept in legalities but absent from legalFormats`() {
        val card = fetcher.parseCard(
            obj("""{"name":"Lurrus","color_identity":["W","B"],"type_line":"Creature",
               "legalities":{"modern":"banned","legacy":"banned","vintage":"restricted"}}""")
        )!!
        assertTrue(card.legalFormats.isEmpty()) // banned/restricted aren't "legal"
        assertEquals("banned", card.legalities["modern"])
        assertEquals("restricted", card.legalities["vintage"])
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

    // --- rulings -------------------------------------------------------

    @Test
    fun `parseRulings maps the data array, skipping blank comments`() {
        val rulings = fetcher.parseRulings(
            obj("""{"data":[
              {"published_at":"2021-03-19","comment":"First."},
              {"published_at":"2022-02-02","comment":""},
              {"comment":"No date."}
            ]}""")
        )
        assertEquals(2, rulings.size)
        assertEquals("2021-03-19", rulings[0].publishedAt)
        assertEquals("First.", rulings[0].comment)
        assertEquals("", rulings[1].publishedAt) // missing date → blank
        assertEquals("No date.", rulings[1].comment)
    }

    @Test
    fun `parseRulings tolerates a missing data array`() {
        assertTrue(fetcher.parseRulings(obj("""{"object":"error"}""")).isEmpty())
    }

    @Test
    fun `fetchRulings resolves the card then its rulings`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.toString().contains("/rulings")) {
                respond(
                    """{"data":[{"published_at":"2021-03-19","comment":"It works like this."}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            } else {
                respond(
                    """{"name":"Doubling Season","scryfall_uri":"https://scryfall.com/card",
                       "rulings_uri":"https://api.scryfall.com/cards/abc/rulings"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }
        val rulings = fetcherWith(engine).fetchRulings("doubling season")
        assertEquals("Doubling Season", rulings?.cardName)
        assertEquals("https://scryfall.com/card", rulings?.scryfallUri)
        assertEquals(1, rulings?.rulings?.size)
        assertEquals("It works like this.", rulings?.rulings?.first()?.comment)
        // Two calls: the fuzzy named lookup, then the rulings uri.
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `fetchRulings returns an empty list when the card has no rulings`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.toString().contains("/rulings")) {
                respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(
                    """{"name":"Plains","scryfall_uri":"https://scryfall.com/p","rulings_uri":"https://api.scryfall.com/cards/p/rulings"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }
        val rulings = fetcherWith(engine).fetchRulings("Plains")
        assertEquals("Plains", rulings?.cardName)
        assertTrue(rulings!!.rulings.isEmpty())
    }

    @Test
    fun `fetchRulings returns null when no card matches (404)`() = runBlocking {
        val rulings = fetcherWith(MockEngine { respondError(HttpStatusCode.NotFound) }).fetchRulings("zzznotacard")
        assertNull(rulings)
    }

    @Test
    fun `fetchRulings returns null for a blank name without calling the network`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        assertNull(fetcherWith(engine).fetchRulings("   "))
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `fetchRulings yields no rulings when the rulings call errors but the card resolved`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.toString().contains("/rulings")) {
                respondError(HttpStatusCode.InternalServerError)
            } else {
                respond(
                    """{"name":"Bolt","scryfall_uri":"u","rulings_uri":"https://api.scryfall.com/cards/b/rulings"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }
        val rulings = fetcherWith(engine).fetchRulings("Bolt")
        assertEquals("Bolt", rulings?.cardName)
        assertTrue(rulings!!.rulings.isEmpty())
    }

    // --- set lookup ----------------------------------------------------

    @Test
    fun `parseSet maps the headline facts and uppercases the code`() {
        val set = fetcher.parseSet(
            obj("""{"code":"vow","name":"Innistrad: Crimson Vow","set_type":"expansion",
               "released_at":"2021-11-19","card_count":277,
               "icon_svg_uri":"https://img/vow.svg","scryfall_uri":"https://scryfall.com/sets/vow"}""")
        )!!
        assertEquals("VOW", set.code)
        assertEquals("Innistrad: Crimson Vow", set.name)
        assertEquals("expansion", set.setType)
        assertEquals("2021-11-19", set.releasedAt)
        assertEquals(277, set.cardCount)
        assertEquals("https://img/vow.svg", set.iconUrl)
    }

    @Test
    fun `parseSet returns null without a code or name`() {
        assertNull(fetcher.parseSet(obj("""{"name":"No Code"}""")))
        assertNull(fetcher.parseSet(obj("""{"code":"x"}""")))
    }

    @Test
    fun `fetchSet resolves a set by code`() = runBlocking {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().contains("/sets/vow"))
            respond(
                """{"code":"vow","name":"Innistrad: Crimson Vow","set_type":"expansion","card_count":277}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val set = fetcherWith(engine).fetchSet("VOW")
        assertEquals("VOW", set?.code)
        assertEquals(277, set?.cardCount)
    }

    @Test
    fun `fetchSet returns null on a 404 or blank code`() = runBlocking {
        assertNull(fetcherWith(MockEngine { respondError(HttpStatusCode.NotFound) }).fetchSet("zzz"))
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        assertNull(fetcherWith(engine).fetchSet("  "))
        assertEquals(0, engine.requestHistory.size)
    }

    // --- combos (Commander Spellbook) ----------------------------------

    @Test
    fun `parseCombos maps uses, produces and the combo url`() {
        val combos = fetcher.parseCombos(
            obj("""{"results":[
              {"id":"42-7","uses":[{"card":{"name":"Thassa's Oracle"}},{"card":{"name":"Demonic Consultation"}}],
               "produces":[{"feature":{"name":"Win the game"}}]},
              {"id":"","uses":[],"produces":[]},
              {"id":"99","uses":[],"produces":[]}
            ]}""")
        )
        assertEquals(1, combos.size) // blank id and empty uses+produces are dropped
        assertEquals("42-7", combos[0].id)
        assertEquals(listOf("Thassa's Oracle", "Demonic Consultation"), combos[0].uses)
        assertEquals(listOf("Win the game"), combos[0].produces)
        assertEquals("https://commanderspellbook.com/combo/42-7/", combos[0].url)
    }

    @Test
    fun `parseCombos tolerates a missing results array`() {
        assertTrue(fetcher.parseCombos(obj("""{"detail":"nope"}""")).isEmpty())
    }

    @Test
    fun `fetchCombos resolves a card's combos from Commander Spellbook`() = runBlocking {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().contains("/variants/"))
            respond(
                """{"results":[{"id":"7","uses":[{"card":{"name":"Kiki-Jiki"}}],"produces":[{"feature":{"name":"Infinite haste creatures"}}]}]}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val combos = fetcherWith(engine).fetchCombos("Kiki-Jiki, Mirror Breaker")
        assertEquals("Kiki-Jiki, Mirror Breaker", combos?.cardName)
        assertEquals(1, combos?.combos?.size)
        assertEquals("Infinite haste creatures", combos?.combos?.first()?.produces?.first())
    }

    @Test
    fun `fetchCombos returns an empty list when the card is in no combos`() = runBlocking {
        val combos = fetcherWith(MockEngine { respond("""{"results":[]}""", HttpStatusCode.OK, jsonHeaders) })
            .fetchCombos("Plains")
        assertEquals("Plains", combos?.cardName)
        assertTrue(combos!!.combos.isEmpty())
    }

    @Test
    fun `fetchCombos returns null on a transport failure or blank name`() = runBlocking {
        assertNull(fetcherWith(MockEngine { throw IOException("down") }).fetchCombos("Bolt"))
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        assertNull(fetcherWith(engine).fetchCombos("   "))
        assertEquals(0, engine.requestHistory.size)
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
