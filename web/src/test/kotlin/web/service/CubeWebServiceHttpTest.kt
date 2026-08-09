package web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpRequest
import java.time.Duration

/**
 * The Scryfall-facing half of [CubeWebService], driven through a fake
 * [HttpTransport].
 *
 * These paths — status handling, pagination, batching, the response cache and
 * the throttle — used to be reachable only by really calling Scryfall, so none
 * of them were covered. The fake records every request, which lets the tests
 * assert what *wasn't* sent (a cache hit, a short-circuit on blank input) as
 * well as what was.
 */
class CubeWebServiceHttpTest {

    /** Canned replies keyed by a substring of the URL, plus a request log. */
    private class FakeTransport(
        private val replies: MutableList<(HttpRequest) -> HttpReply> = mutableListOf(),
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var failWith: IOException? = null

        override fun send(request: HttpRequest): HttpReply {
            requests += request
            failWith?.let { throw it }
            val reply = replies.removeFirstOrNull() ?: error("unexpected request: ${request.uri()}")
            return reply(request)
        }

        fun queue(status: Int, body: String) = apply { replies += { HttpReply(status, body) } }
        fun queue(reply: (HttpRequest) -> HttpReply) = apply { replies += reply }

        val urls: List<String> get() = requests.map { it.uri().toString() }
        val count: Int get() = requests.size
    }

    private fun service(transport: HttpTransport, throttle: ScryfallThrottle = noWait()) =
        CubeWebService(throttle, transport)

    /** A throttle that never sleeps, so the tests don't either. */
    private fun noWait() = ScryfallThrottle(Duration.ZERO, Duration.ofSeconds(3), { 0L }, {})

    /** A throttle with no room left: every call is refused. */
    private fun refusing() = ScryfallThrottle(Duration.ofDays(1), Duration.ZERO, { 0L }, {}).also { it.awaitSlot() }

    private fun card(name: String, extra: String = "") =
        """{"name":"$name","color_identity":["R"],"type_line":"Instant","cmc":1.0,
           "image_uris":{"small":"s.jpg","normal":"n.jpg"}$extra}"""

    private fun searchPage(vararg names: String, nextPage: String? = null) =
        """{"has_more":${nextPage != null}${nextPage?.let { ""","next_page":"$it"""" } ?: ""},
            "data":[${names.joinToString(",") { card(it) }}]}"""

    private fun failure(result: CubeResult<*>) = assertInstanceOf(CubeResult.Failure::class.java, result).error

    private fun <T> success(result: CubeResult<T>): T = when (result) {
        is CubeResult.Success -> result.value
        is CubeResult.Failure -> throw AssertionError("expected success, got: ${result.error}")
    }

    // --- card lookup ---------------------------------------------------

    @Test
    fun `card maps a resolved lookup`() {
        val http = FakeTransport().queue(200, card("Lightning Bolt"))

        val view = success(service(http).card("bolt"))

        assertEquals("Lightning Bolt", view.name)
        assertTrue(http.urls.single().contains("fuzzy=bolt")) { "expected a fuzzy lookup, got ${http.urls}" }
    }

    @Test
    fun `card names the thing you searched for when it isn't found`() {
        val http = FakeTransport().queue(404, "{}")

        assertTrue(failure(service(http).card("zzzz")).contains("zzzz"))
    }

    @Test
    fun `card reports rate limiting as rate limiting, not as a status code`() {
        val http = FakeTransport().queue(429, "")

        val error = failure(service(http).card("bolt"))

        assertTrue(error.contains("rate-limiting")) { "expected the friendly message, got: $error" }
        assertTrue(!error.contains("429")) { "a 429 shouldn't leak the status code at people" }
    }

    @Test
    fun `card surfaces any other status`() {
        assertTrue(failure(service(FakeTransport().queue(503, "")).card("bolt")).contains("503"))
    }

    @Test
    fun `card reports an unreachable Scryfall`() {
        val http = FakeTransport().also { it.failWith = IOException("connection reset") }

        assertTrue(failure(service(http).card("bolt")).contains("connection reset"))
    }

    @Test
    fun `card rejects a blank name without sending anything`() {
        val http = FakeTransport()

        assertTrue(failure(service(http).card("   ")).contains("Enter a card name"))
        assertEquals(0, http.count)
    }

    @Test
    fun `card handles a 200 that isn't a card`() {
        val http = FakeTransport().queue(200, """{"object":"error"}""")

        assertTrue(failure(service(http).card("bolt")).contains("unexpected card"))
    }

    @Test
    fun `card is paced by the throttle like every other lookup`() {
        // It used to build its own request and skip the throttle entirely.
        val http = FakeTransport().queue(200, card("Lightning Bolt"))

        val error = failure(service(http, refusing()).card("bolt"))

        assertTrue(error.contains("queued")) { "expected the busy message, got: $error" }
        assertEquals(0, http.count) { "a refused call must not reach the network" }
    }

    // --- rulings (two hops: the card, then its rulings_uri) -------------

    @Test
    fun `rulings follows the card's rulings uri`() {
        val http = FakeTransport()
            .queue(200, card("Doubling Season", ""","rulings_uri":"https://api.scryfall.com/x/rulings","scryfall_uri":"https://scryfall.com/x""""))
            .queue(200, """{"data":[{"published_at":"2020-01-01","comment":"It doubles."}]}""")

        val view = success(service(http).rulings("doubling"))

        assertEquals("Doubling Season", view.name)
        assertEquals(listOf("It doubles."), view.rulings.map { it.comment })
        assertEquals(2, http.count)
    }

    @Test
    fun `a card with no rulings uri is a success with no rulings`() {
        val http = FakeTransport().queue(200, card("Forest"))

        assertTrue(success(service(http).rulings("forest")).rulings.isEmpty())
        assertEquals(1, http.count) { "nothing to follow, so no second call" }
    }

    @Test
    fun `a failing rulings fetch still returns the card`() {
        val http = FakeTransport()
            .queue(200, card("Doubling Season", ""","rulings_uri":"https://api.scryfall.com/x/rulings""""))
            .queue(500, "")

        val view = success(service(http).rulings("doubling"))

        assertEquals("Doubling Season", view.name)
        assertTrue(view.rulings.isEmpty()) { "a broken rulings call shouldn't sink the lookup" }
    }

    @Test
    fun `rulings reports an unknown card and rate limiting distinctly`() {
        assertTrue(failure(service(FakeTransport().queue(404, "")).rulings("zzz")).contains("zzz"))
        assertTrue(failure(service(FakeTransport().queue(429, "")).rulings("x")).contains("rate-limiting"))
    }

    // --- set ------------------------------------------------------------

    @Test
    fun `set maps a resolved set and tidies its type`() {
        val http = FakeTransport().queue(
            200,
            """{"code":"vow","name":"Crimson Vow","set_type":"expansion_set","released_at":"2021-11-19",
                "card_count":277,"icon_svg_uri":"i.svg","scryfall_uri":"https://scryfall.com/sets/vow"}""",
        )

        val view = success(service(http).set(" VOW "))

        assertEquals("VOW", view.code) { "the code is displayed upper-cased" }
        assertEquals("expansion set", view.setType) { "underscores are for JSON, not for people" }
        assertEquals(277, view.cardCount)
    }

    @Test
    fun `set reports a bad code, rate limiting and other statuses`() {
        assertTrue(failure(service(FakeTransport().queue(404, "")).set("zzz")).contains("zzz"))
        assertTrue(failure(service(FakeTransport().queue(429, "")).set("vow")).contains("rate-limiting"))
        assertTrue(failure(service(FakeTransport().queue(500, "")).set("vow")).contains("500"))
        assertEquals(0, FakeTransport().let { failure(service(it).set(" ")); it.count })
    }

    // --- combos (Commander Spellbook, not Scryfall) ---------------------

    @Test
    fun `combos maps the variants response`() {
        val http = FakeTransport().queue(
            200,
            """{"results":[{"id":"1-2","uses":[{"card":{"name":"Thassa's Oracle"}}],
                "produces":[{"feature":{"name":"Win the game"}}]}]}""",
        )

        val combos = success(service(http).combos("Thassa's Oracle")).combos

        assertEquals(listOf("Thassa's Oracle"), combos.single().uses)
        assertEquals(listOf("Win the game"), combos.single().produces)
    }

    @Test
    fun `combos names Spellbook, not Scryfall, when Spellbook is down`() {
        val error = failure(service(FakeTransport().queue(502, "")).combos("Bolt"))

        assertTrue(error.contains("Commander Spellbook")) { "expected the right service named, got: $error" }
    }

    // --- pool search (preview / generate) -------------------------------

    @Test
    fun `preview fetches a pool and reports its size`() {
        val http = FakeTransport().queue(200, searchPage("Bolt", "Shock"))

        val data = success(service(http).preview("c:r", 15))

        assertEquals(2, data.poolSize)
        assertTrue(http.urls.single().contains("q=c%3Ar"))
    }

    @Test
    fun `a paged search follows next_page until it runs out`() {
        val http = FakeTransport()
            .queue(200, searchPage("Bolt", nextPage = "https://api.scryfall.com/cards/search?page=2"))
            .queue(200, searchPage("Shock"))

        assertEquals(2, success(service(http).preview("c:r", 15)).poolSize)
        assertEquals(2, http.count)
        assertTrue(http.urls[1].contains("page=2")) { "the second call must follow next_page" }
    }

    @Test
    fun `a search that pages forever is cut off`() {
        val http = FakeTransport()
        repeat(20) { http.queue(200, searchPage("Bolt", nextPage = "https://api.scryfall.com/cards/search?page=9")) }

        success(service(http).preview("c:r", 15))

        assertEquals(10, http.count) { "expected the page cap to stop the walk" }
    }

    @Test
    fun `an empty result set is an error, not an empty cube`() {
        val http = FakeTransport().queue(200, """{"has_more":false,"data":[]}""")

        assertTrue(failure(service(http).preview("c:r", 15)).contains("No usable cards"))
    }

    @Test
    fun `search statuses map to their own messages`() {
        assertTrue(failure(service(FakeTransport().queue(404, "")).preview("zzz", 15)).contains("No cards matched"))
        assertTrue(failure(service(FakeTransport().queue(429, "")).preview("c:r", 15)).contains("rate-limiting"))
        assertTrue(failure(service(FakeTransport().queue(500, "")).preview("c:r", 15)).contains("500"))
    }

    @Test
    fun `a blank query never reaches the network`() {
        val http = FakeTransport()

        assertTrue(failure(service(http).preview("  ", 15)).contains("Scryfall search"))
        assertEquals(0, http.count)
    }

    @Test
    fun `generate deals from a fetched pool`() {
        val http = FakeTransport().queue(200, searchPage("Bolt", "Shock", "Forest", "Island"))

        val data = success(service(http).generate("c:r", packCount = 2, packSize = 2, balanced = true))

        assertEquals(2, data.packCount)
        assertEquals(listOf(2, 2), data.packs.map { it.size })
    }

    @Test
    fun `generate refuses a pool that can't fill the packs`() {
        val http = FakeTransport().queue(200, searchPage("Bolt"))

        assertTrue(failure(service(http).generate("c:r", 24, 15, true)).contains("Not enough cards"))
    }

    // --- the pool cache --------------------------------------------------

    @Test
    fun `the same query is only fetched once`() {
        val http = FakeTransport().queue(200, searchPage("Bolt", "Shock"))
        val service = service(http)

        service.preview("c:r", 15)
        service.preview("c:r", 15)
        service.generate("c:r", 1, 2, true)

        assertEquals(1, http.count) { "a draft pod opening one cube shouldn't refetch it per person" }
    }

    @Test
    fun `a different query is fetched separately`() {
        val http = FakeTransport().queue(200, searchPage("Bolt")).queue(200, searchPage("Forest"))
        val service = service(http)

        service.preview("c:r", 15)
        service.preview("c:g", 15)

        assertEquals(2, http.count)
    }

    @Test
    fun `a failed lookup is not remembered as an answer`() {
        val http = FakeTransport().queue(500, "").queue(200, searchPage("Bolt"))
        val service = service(http)

        assertTrue(failure(service.preview("c:r", 15)).contains("500"))
        assertEquals(1, success(service.preview("c:r", 15)).poolSize) { "the retry must really retry" }
    }

    @Test
    fun `a cached pool is served without a throttle slot`() {
        val http = FakeTransport().queue(200, searchPage("Bolt", "Shock"))
        // One throttle, then refuse: the second call can only succeed from cache.
        val throttle = ScryfallThrottle(Duration.ofDays(1), Duration.ZERO, { 0L }, {})
        val service = service(http, throttle)

        assertEquals(2, success(service.preview("c:r", 15)).poolSize)
        assertEquals(2, success(service.preview("c:r", 15)).poolSize)
        assertEquals(1, http.count)
    }

    // --- pasted lists (the /cards/collection path) -----------------------

    @Test
    fun `a pasted list is resolved by name and keeps its quantities`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")},${card("Forest")}]}""")

        val data = success(service(http).previewList("3 Lightning Bolt\nForest", 15))

        assertEquals(4, data.poolSize) { "three Bolts plus a Forest" }
        assertEquals(1, http.count)
        assertTrue(http.requests.single().method() == "POST")
    }

    @Test
    fun `a long list is resolved in batches of seventy-five`() {
        val names = (1..160).map { "Card $it" }
        val http = FakeTransport()
        repeat(3) { http.queue { HttpReply(200, """{"data":[${card("Lightning Bolt")}]}""") } }

        service(http).previewList(names.joinToString("\n"), 15)

        assertEquals(3, http.count) { "160 names is three collection posts, not one giant one" }
    }

    @Test
    fun `names Scryfall doesn't know come back as not-found`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")}]}""")

        val data = success(service(http).previewList("Lightning Bolt\nDefinitely Not A Card", 15))

        assertEquals(listOf("Definitely Not A Card"), data.notFound)
    }

    @Test
    fun `a list nothing resolves from is an error`() {
        val http = FakeTransport().queue(200, """{"data":[]}""")

        assertTrue(failure(service(http).previewList("Nope\nAlso Nope", 15)).contains("matched Scryfall"))
    }

    @Test
    fun `a rate-limited collection lookup says so`() {
        val http = FakeTransport().queue(429, "")

        assertTrue(failure(service(http).previewList("Bolt", 15)).contains("rate-limiting"))
    }

    @Test
    fun `an unreachable collection lookup is reported, not swallowed`() {
        val http = FakeTransport().also { it.failWith = IOException("timed out") }

        assertTrue(failure(service(http).previewList("Bolt", 15)).contains("timed out"))
    }

    @Test
    fun `the same set of names is only resolved once`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")}]}""")
        val service = service(http)

        service.previewList("Lightning Bolt", 15)
        service.previewList("Lightning Bolt", 15)

        assertEquals(1, http.count)
    }

    @Test
    fun `two lists differing only in order share one lookup`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")},${card("Forest")}]}""")
        val service = service(http)

        service.previewList("Lightning Bolt\nForest", 15)
        service.previewList("Forest\nLightning Bolt", 15)

        assertEquals(1, http.count) { "the cache key is order-independent by design" }
    }

    // --- reloading a pack list end to end --------------------------------

    @Test
    fun `packsFromText resolves a downloaded deal into its packs`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")},${card("Forest")}]}""")
        val text = """
            # cube:vintage — 2 packs of 1 — 2026-08-09

            == Pack 1 (1 card) ==
              Lightning Bolt

            == Pack 2 (1 card) ==
              Forest
        """.trimIndent()

        val data = success(service(http).packsFromText(text))

        assertEquals(2, data.packCount)
        assertEquals(listOf("Lightning Bolt"), data.packs[0].map { it.name })
        assertEquals(listOf("Forest"), data.packs[1].map { it.name })
        assertEquals("# cube:vintage — 2 packs of 1 — 2026-08-09", data.provenance)
    }

    @Test
    fun `packsFromText reports an unreachable Scryfall rather than half a deal`() {
        val http = FakeTransport().also { it.failWith = IOException("no route to host") }

        assertTrue(failure(service(http).packsFromText("== Pack 1 ==\n  Bolt\n")).contains("no route to host"))
    }

    // --- legality (resolves a list, then checks it) ----------------------

    @Test
    fun `checkLegality resolves the deck and reports its banned cards`() {
        val banned = """{"name":"Lurrus","color_identity":["W","B"],"type_line":"Creature",
                         "legalities":{"modern":"banned"}}"""
        val http = FakeTransport().queue(200, """{"data":[$banned]}""")

        val report = success(service(http).checkLegality("Lurrus", "modern"))

        assertEquals(listOf("Lurrus"), report.banned)
        assertTrue(!report.legal)
    }

    @Test
    fun `checkLegality rejects an unknown format before resolving anything`() {
        val http = FakeTransport()

        assertTrue(failure(service(http).checkLegality("Bolt", "yugioh")).contains("Unknown format"))
        assertEquals(0, http.count)
    }

    @Test
    fun `set copes with the optional fields Scryfall may omit`() {
        val http = FakeTransport().queue(200, """{"code":"tst","name":"Tiny Set","set_type":"funny"}""")

        val view = success(service(http).set("tst"))

        assertEquals("Tiny Set", view.name)
        assertEquals(0, view.cardCount)
        assertTrue(view.releasedAt == null && view.iconUrl == null && view.scryfallUri == null) {
            "absent fields should be null, not empty strings"
        }
    }

    @Test
    fun `set handles a 200 that isn't a set`() {
        val http = FakeTransport().queue(200, """{"object":"error"}""")

        assertTrue(failure(service(http).set("tst")).contains("unexpected set"))
    }

    @Test
    fun `generateList deals from a resolved list`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")},${card("Forest")}]}""")

        val data = success(service(http).generateList("2 Lightning Bolt\n2 Forest", 2, 2, true))

        assertEquals(2, data.packCount)
        assertEquals(listOf(2, 2), data.packs.map { it.size })
    }

    @Test
    fun `a search that fills the cap says it was capped`() {
        val many = (1..760).joinToString(",") { card("Card $it") }
        val http = FakeTransport().queue(200, """{"has_more":false,"data":[$many]}""")

        val data = success(service(http).search("c:r"))

        assertEquals(750, data.total) { "the pool is capped" }
        assertTrue(data.capped) { "and the page is told, rather than silently truncating" }
    }

    @Test
    fun `a deal too big for the pool cap keeps whole packs and says what it dropped`() {
        val http = FakeTransport().queue(200, """{"data":[${card("Lightning Bolt")}]}""")
        // 800 single-card packs is past the 750 cap; the tail must go as packs.
        val text = (1..800).joinToString("\n\n") { "== Pack $it (1 card) ==\n  Lightning Bolt" }

        val data = success(service(http).packsFromText(text))

        assertEquals(750, data.packCount)
        assertTrue(data.note!!.contains("800 packs")) { "expected the drop to be stated, got: ${data.note}" }
    }

    // --- search (the card-grid tool) -------------------------------------

    @Test
    fun `search returns the matches and echoes the query it built`() {
        val http = FakeTransport().queue(200, searchPage("Bolt", "Shock"))

        val data = success(service(http).search("c:r t:instant"))

        assertEquals(2, data.total)
        assertEquals("c:r t:instant", data.query)
        assertEquals(listOf("Bolt", "Shock"), data.cards.map { it.name })
    }
}
