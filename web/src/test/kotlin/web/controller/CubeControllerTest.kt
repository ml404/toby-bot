package web.controller

import database.dto.user.CubeListDto
import database.dto.user.SharedCubeDto
import database.service.user.CubeListService
import database.service.user.SharedCubeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.Model
import web.service.AnalyticsView
import web.service.CardLookupView
import web.service.CardView
import web.service.CategoryAsFan
import web.service.CategoryGroup
import web.service.ColorPairView
import web.service.ColorPipView
import web.service.CubeResult
import web.service.CubeWebService
import web.service.CurveBucketView
import web.service.DiffData
import web.service.DiffLineView
import web.service.DuplicateView
import web.service.GenerateData
import web.service.PreviewData
import web.service.RarityCountView
import web.service.RulingView
import web.service.RulingsView
import web.service.TypeCountView
import java.time.Instant

class CubeControllerTest {

    private val discordId = 100L

    private lateinit var service: CubeWebService
    private lateinit var cubeLists: CubeListService
    private lateinit var sharedCubes: SharedCubeService
    private lateinit var controller: CubeController

    private fun loggedIn() = mockk<OAuth2User> {
        every { getAttribute<String>("id") } returns discordId.toString()
        every { getAttribute<String>("username") } returns "matt"
    }
    private fun anon() = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }

    /** A sample cube report for preview fixtures. */
    private fun analytics(
        types: List<TypeCountView> = listOf(TypeCountView("Creature", 1, 0.5)),
        duplicates: List<DuplicateView> = emptyList(),
    ) = AnalyticsView(
        curve = listOf(CurveBucketView("0", 0), CurveBucketView("1", 1)),
        averageManaValue = 1.5,
        nonLandCount = 1,
        types = types,
        rarities = listOf(RarityCountView("Common", 1, 0.5)),
        duplicates = duplicates,
        colorPairs = listOf(ColorPairView("Azorius (WU)", 3)),
        colorPips = listOf(ColorPipView("White", 5)),
    )

    @BeforeEach
    fun setup() {
        service = mockk()
        cubeLists = mockk(relaxed = true)
        sharedCubes = mockk(relaxed = true)
        controller = CubeController(service, cubeLists, sharedCubes)
    }

    @Test
    fun `page returns the cube template and passes displayName to the model`() {
        val model = mockk<Model>(relaxed = true)
        val user = mockk<OAuth2User> { every { getAttribute<String>("username") } returns "matt" }

        assertEquals("cube", controller.page(user, model))

        verify(exactly = 1) { model.addAttribute("username", "matt") }
    }

    @Test
    fun `page anon user falls back to literal User`() {
        val model = mockk<Model>(relaxed = true)
        controller.page(null, model)
        verify(exactly = 1) { model.addAttribute("username", "User") }
    }

    @Test
    fun `asfan returns 200 with the value on success`() {
        every { service.asFan(60, 540, 15) } returns CubeResult.ok(1.6667)
        val response = controller.asFan(60, 540, 15)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(1.6667, response.body!!.value)
    }

    @Test
    fun `asfan returns 400 with the error on failure`() {
        every { service.asFan(1, 0, 15) } returns CubeResult.error("Cube size must be positive (was 0)")
        val response = controller.asFan(1, 0, 15)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("Cube size must be positive (was 0)", response.body!!.error)
    }

    @Test
    fun `preview returns 200 with the card groups on success`() {
        val data = PreviewData(
            query = "set:vow", poolSize = 277, packSize = 15,
            groups = listOf(
                CategoryGroup(
                    "White", 50, 2.7,
                    listOf(
                        CardView("Sigarda's Splendor", "https://img/sigarda.jpg", "https://img/sigarda-lg.jpg", "Enchantment", 4.0),
                        CardView("Sungold Sentinel", null, null, "Creature — Human", 2.0),
                    ),
                )
            ),
            analytics = analytics(),
        )
        every { service.preview("set:vow", 15) } returns CubeResult.ok(data)

        val response = controller.preview("set:vow", 15)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(277, response.body!!.poolSize)
        assertEquals(1, response.body!!.groups.size)
        val cards = response.body!!.groups.first().cards
        assertEquals(listOf("Sigarda's Splendor", "Sungold Sentinel"), cards.map { it.name })
        assertEquals("https://img/sigarda.jpg", cards.first().imageUrl)
        // The cube report flows through the response.
        assertEquals(1.5, response.body!!.analytics!!.averageManaValue)
        assertEquals("Creature", response.body!!.analytics!!.types.first().type)
    }

    @Test
    fun `card returns 200 with the looked-up card`() {
        every { service.card("Ragavan") } returns CubeResult.ok(
            CardLookupView("Ragavan, Nimble Pilferer", "s.jpg", "n.jpg", null, "Legendary Creature", 1.0, "{R}", "Mythic", listOf("Red"))
        )
        val response = controller.card("Ragavan")
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals("Ragavan, Nimble Pilferer", response.body!!.card!!.name)
    }

    @Test
    fun `card returns 400 when nothing matches`() {
        every { service.card(any()) } returns CubeResult.error("No card found matching “zzz”.")
        val response = controller.card("zzz")
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `rulings returns 200 with the looked-up rulings`() {
        every { service.rulings("Doubling Season") } returns CubeResult.ok(
            RulingsView(
                "Doubling Season", "https://scryfall.com/card",
                listOf(RulingView("2021-03-19", "If two replacement effects apply, you choose the order.")),
            )
        )
        val response = controller.rulings("Doubling Season")
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals("Doubling Season", response.body!!.name)
        assertEquals(1, response.body!!.rulings.size)
        assertEquals("2021-03-19", response.body!!.rulings.first().publishedAt)
    }

    @Test
    fun `rulings returns 400 when nothing matches`() {
        every { service.rulings(any()) } returns CubeResult.error("No card found matching “zzz”.")
        val response = controller.rulings("zzz")
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertTrue(response.body!!.rulings.isEmpty())
    }

    @Test
    fun `diff returns 200 with the comparison`() {
        every { service.diff("A list", "B list") } returns CubeResult.ok(
            DiffData(
                added = listOf(DiffLineView("Shock", 0, 1)),
                removed = emptyList(),
                changed = listOf(DiffLineView("Forest", 3, 5)),
                sizeA = 4, sizeB = 6,
            )
        )
        val response = controller.diff(CubeDiffRequest("A list", "B list"))
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals("Shock", response.body!!.added.first().name)
        assertEquals(5, response.body!!.changed.first().to)
        assertEquals(4, response.body!!.sizeA)
    }

    @Test
    fun `diff returns 400 on failure`() {
        every { service.diff(any(), any()) } returns CubeResult.error("Paste a card list into both sides to compare.")
        val response = controller.diff(CubeDiffRequest("", ""))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `preview returns 400 on failure`() {
        every { service.preview("set:zzz", 15) } returns CubeResult.error("No cards matched that query.")
        val response = controller.preview("set:zzz", 15)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertEquals("No cards matched that query.", response.body!!.error)
    }

    @Test
    fun `generate returns 200 with packs on success`() {
        val data = GenerateData(
            query = "cube:vintage", poolSize = 540, packCount = 24, packSize = 15, balanced = true,
            packs = listOf(
                listOf(
                    CardView("Bolt", "https://img/bolt.jpg", "https://img/bolt-lg.jpg", "Instant", 1.0),
                    CardView("Forest", null, null, "Basic Land — Forest", 0.0),
                ),
                listOf(CardView("Sol Ring", "https://img/solring.jpg", "https://img/solring-lg.jpg", "Artifact", 1.0)),
            ),
            distribution = listOf(CategoryAsFan("Red", 100, 2.7)),
        )
        every { service.generate("cube:vintage", 24, 15, true) } returns CubeResult.ok(data)

        val response = controller.generate("cube:vintage", 24, 15, true)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.ok)
        assertEquals(24, body.packCount)
        assertEquals(2, body.packs.size)
        assertEquals(listOf("Bolt", "Forest"), body.packs.first().map { it.name })
        assertEquals("https://img/bolt.jpg", body.packs.first().first().imageUrl)
    }

    @Test
    fun `generate returns 400 when the pool is too small`() {
        every { service.generate("t:angel", 24, 15, true) } returns
            CubeResult.error("Not enough cards: need 360 but the pool only has 30.")
        val response = controller.generate("t:angel", 24, 15, true)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
        assertTrue(response.body!!.packs.isEmpty())
    }

    @Test
    fun `previewList resolves a pasted list and surfaces unresolved names`() {
        val data = PreviewData(
            query = "your list", poolSize = 2, packSize = 15,
            groups = listOf(CategoryGroup("Red", 2, 2.0, listOf(CardView("Bolt", null, null, "Instant", 1.0)))),
            analytics = analytics(),
            notFound = listOf("Definitely Not A Card"),
        )
        every { service.previewList("3 Bolt\nDefinitely Not A Card", 15) } returns CubeResult.ok(data)

        val response = controller.previewList(CubeListPreviewRequest("3 Bolt\nDefinitely Not A Card", 15))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(listOf("Definitely Not A Card"), response.body!!.notFound)
        assertEquals(1, response.body!!.groups.size)
    }

    @Test
    fun `generateList deals from a pasted list and surfaces unresolved names`() {
        val data = GenerateData(
            query = "your list", poolSize = 60, packCount = 4, packSize = 15, balanced = true,
            packs = listOf(listOf(CardView("Bolt", null, null, "Instant", 1.0))),
            distribution = listOf(CategoryAsFan("Red", 60, 15.0)),
            notFound = listOf("Mystery Card"),
        )
        every { service.generateList("60 Bolt\nMystery Card", 4, 15, true) } returns CubeResult.ok(data)

        val response = controller.generateList(CubeListGenerateRequest("60 Bolt\nMystery Card", 4, 15, true))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.ok)
        assertEquals(listOf("Mystery Card"), response.body!!.notFound)
        assertEquals(1, response.body!!.packs.size)
    }

    @Test
    fun `previewList passes a truncation note through to the response`() {
        val data = PreviewData(
            query = "your list", poolSize = 750, packSize = 15, groups = emptyList(),
            analytics = analytics(),
            notFound = emptyList(), note = "Your list resolved to 900 cards; only the first 750 were used.",
        )
        every { service.previewList(any(), any()) } returns CubeResult.ok(data)

        val response = controller.previewList(CubeListPreviewRequest("big list", 15))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("Your list resolved to 900 cards; only the first 750 were used.", response.body!!.note)
    }

    @Test
    fun `previewList returns 400 when the list resolves to nothing`() {
        every { service.previewList(any(), any()) } returns CubeResult.error("None of those card names matched Scryfall. Check the spelling?")
        val response = controller.previewList(CubeListPreviewRequest("asdf\nqwer", 15))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.ok)
    }

    @Test
    fun `generate forwards the raw params to the service`() {
        every { service.generate("set:vow", 8, 15, false) } returns
            CubeResult.error("whatever")
        controller.generate("set:vow", 8, 15, false)
        verify(exactly = 1) { service.generate("set:vow", 8, 15, false) }
    }

    // --- saved lists (account-bound) -----------------------------------

    @Test
    fun `page flags whether the user is logged in`() {
        val model = mockk<Model>(relaxed = true)
        controller.page(loggedIn(), model)
        verify { model.addAttribute("loggedIn", true) }

        val anonModel = mockk<Model>(relaxed = true)
        controller.page(null, anonModel)
        verify { anonModel.addAttribute("loggedIn", false) }
    }

    @Test
    fun `savedLists returns the user's lists`() {
        every { cubeLists.listForUser(discordId) } returns listOf(
            CubeListDto(discordId, "My Cube", "Bolt\nForest", Instant.EPOCH, Instant.EPOCH),
        )
        val response = controller.savedLists(loggedIn())
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf("My Cube"), response.body!!.map { it.name })
        assertEquals("Bolt\nForest", response.body!!.first().cards)
    }

    @Test
    fun `savedLists rejects an anonymous user with 401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.savedLists(anon()).statusCode)
        verify(exactly = 0) { cubeLists.listForUser(any()) }
    }

    @Test
    fun `saveList upserts and returns the saved list`() {
        every { cubeLists.get(discordId, "My Cube") } returns null
        every { cubeLists.listForUser(discordId) } returns emptyList()
        every { cubeLists.save(discordId, "My Cube", "Bolt\nForest", any()) } returns
            CubeListDto(discordId, "My Cube", "Bolt\nForest", Instant.EPOCH, Instant.EPOCH)

        val response = controller.saveList(SaveCubeListRequest("My Cube", "Bolt\nForest"), loggedIn())

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("My Cube", response.body!!.name)
        verify(exactly = 1) { cubeLists.save(discordId, "My Cube", "Bolt\nForest", any()) }
    }

    @Test
    fun `saveList rejects a blank name or empty cards`() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.saveList(SaveCubeListRequest("  ", "Bolt"), loggedIn()).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.saveList(SaveCubeListRequest("Name", "  "), loggedIn()).statusCode)
        verify(exactly = 0) { cubeLists.save(any(), any(), any(), any()) }
    }

    @Test
    fun `saveList rejects an oversized cards payload`() {
        val huge = "x".repeat(100_001)
        val response = controller.saveList(SaveCubeListRequest("My Cube", huge), loggedIn())
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        verify(exactly = 0) { cubeLists.save(any(), any(), any(), any()) }
    }

    @Test
    fun `share rejects an oversized cards payload`() {
        val huge = "x".repeat(100_001)
        val response = controller.share(ShareCubeRequest("My Cube", huge), loggedIn())
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        verify(exactly = 0) { sharedCubes.create(any(), any(), any(), any()) }
    }

    @Test
    fun `saveList rejects an anonymous user with 401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.saveList(SaveCubeListRequest("X", "Bolt"), anon()).statusCode)
    }

    @Test
    fun `saveList refuses a new list once the per-user cap is hit`() {
        every { cubeLists.get(discordId, "New") } returns null
        every { cubeLists.listForUser(discordId) } returns (1..50).map {
            CubeListDto(discordId, "cube$it", "Bolt", Instant.EPOCH, Instant.EPOCH)
        }
        val response = controller.saveList(SaveCubeListRequest("New", "Bolt"), loggedIn())
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        verify(exactly = 0) { cubeLists.save(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteList removes the named list for the user`() {
        val response = controller.deleteList("My Cube", loggedIn())
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        verify(exactly = 1) { cubeLists.delete(discordId, "My Cube") }
    }

    @Test
    fun `deleteList rejects an anonymous user with 401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.deleteList("My Cube", anon()).statusCode)
        verify(exactly = 0) { cubeLists.delete(any(), any()) }
    }

    // --- share links ----------------------------------------------------

    @Test
    fun `share mints a snapshot and returns its token and url`() {
        every { sharedCubes.create(discordId, "My Cube", "Bolt\nForest", any()) } returns
            SharedCubeDto("tok123", discordId, "My Cube", "Bolt\nForest", Instant.EPOCH)

        val response = controller.share(ShareCubeRequest("My Cube", "Bolt\nForest"), loggedIn())

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("tok123", response.body!!.token)
        assertEquals("/cube/c/tok123", response.body!!.url)
    }

    @Test
    fun `share defaults a blank name and rejects blank cards`() {
        every { sharedCubes.create(discordId, "Shared cube", "Bolt", any()) } returns
            SharedCubeDto("t", discordId, "Shared cube", "Bolt", Instant.EPOCH)

        assertEquals(HttpStatus.OK, controller.share(ShareCubeRequest("   ", "Bolt"), loggedIn()).statusCode)
        verify(exactly = 1) { sharedCubes.create(discordId, "Shared cube", "Bolt", any()) }

        assertEquals(HttpStatus.BAD_REQUEST, controller.share(ShareCubeRequest("Name", "  "), loggedIn()).statusCode)
    }

    @Test
    fun `share rejects an anonymous user with 401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.share(ShareCubeRequest("X", "Bolt"), anon()).statusCode)
        verify(exactly = 0) { sharedCubes.create(any(), any(), any(), any()) }
    }

    @Test
    fun `sharedPage pre-loads the cube into the model when the token resolves`() {
        val model = mockk<Model>(relaxed = true)
        every { sharedCubes.get("tok123") } returns SharedCubeDto("tok123", discordId, "My Cube", "Bolt\nForest", Instant.EPOCH)

        assertEquals("cube", controller.sharedPage("tok123", loggedIn(), model))

        verify { model.addAttribute("sharedName", "My Cube") }
        verify { model.addAttribute("sharedCards", "Bolt\nForest") }
    }

    @Test
    fun `sharedPage flags a missing token`() {
        val model = mockk<Model>(relaxed = true)
        every { sharedCubes.get("nope") } returns null

        assertEquals("cube", controller.sharedPage("nope", null, model))

        verify { model.addAttribute("sharedMissing", true) }
    }
}
