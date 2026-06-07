package bot.toby.command.commands.mtg

import common.mtg.CubeCard
import common.mtg.MtgColor
import database.dto.user.CubeListDto
import database.service.user.CubeListService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers [MtgPoolResolver] — the shared pool resolver behind `/mtgcube` and
 * `/mtgdeck`. The tricky bits are the saved-cube path: expanding quantities,
 * tying front-face entries back to the full-name cards Scryfall returns
 * (multi-faced cards), and reporting names that didn't resolve.
 */
class MtgPoolResolverTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var cubeListService: CubeListService
    private lateinit var resolver: MtgPoolResolver

    private val discordId = 100L

    @BeforeEach
    fun setUp() {
        fetcher = mockk()
        cubeListService = mockk()
        resolver = MtgPoolResolver(fetcher, cubeListService)
    }

    private fun savedCube(name: String, cards: String) =
        CubeListDto(discordId = discordId, name = name, cards = cards, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    private fun card(name: String, vararg colors: MtgColor) = CubeCard(name, colors.toSet())

    private fun resolve(saved: String?, query: String?) =
        runBlocking { resolver.resolve(saved, query, discordId) }

    // --- saved-cube path ------------------------------------------------

    @Test
    fun `a saved cube resolves to a pool, expanding quantities, with the cube name as label`() {
        every { cubeListService.get(discordId, "My Cube") } returns
            savedCube("My Cube", "2 Lightning Bolt\nSol Ring")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(card("Lightning Bolt", MtgColor.RED), card("Sol Ring")),
        )

        val result = resolve("My Cube", null) as MtgPoolResolver.PoolResult.Ready

        assertEquals(3, result.pool.size) // 2x Bolt + 1x Sol Ring
        assertEquals(2, result.pool.count { it.name == "Lightning Bolt" })
        assertEquals("saved cube \"My Cube\"", result.label)
        assertTrue(result.notFound.isEmpty())
        assertNull(result.note)
    }

    @Test
    fun `a front-face entry matches the full-name card Scryfall returns for a multi-faced card`() {
        every { cubeListService.get(discordId, "DFC") } returns
            savedCube("DFC", "Huntmaster of the Fells")
        // Scryfall returns the card under its full // name; matchKeys ties it back.
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(card("Huntmaster of the Fells // Ravager of the Fells", MtgColor.RED, MtgColor.GREEN)),
        )

        val result = resolve("DFC", null) as MtgPoolResolver.PoolResult.Ready

        assertEquals(1, result.pool.size)
        assertTrue(result.notFound.isEmpty())
        // The request sends just the front face to Scryfall's collection lookup.
        coVerify { fetcher.fetchByNames(listOf("Huntmaster of the Fells")) }
    }

    @Test
    fun `names Scryfall can't resolve are reported in notFound, distinct, while the rest form the pool`() {
        every { cubeListService.get(discordId, "Mixed") } returns
            savedCube("Mixed", "Lightning Bolt\nNotacard\nNotacard")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(card("Lightning Bolt", MtgColor.RED)),
        )

        val result = resolve("Mixed", null) as MtgPoolResolver.PoolResult.Ready

        assertEquals(1, result.pool.size)
        assertEquals(listOf("Notacard"), result.notFound)
    }

    @Test
    fun `an unknown saved cube name fails`() {
        every { cubeListService.get(discordId, "Ghost") } returns null

        val result = resolve("Ghost", null)

        assertTrue(result is MtgPoolResolver.PoolResult.Failed)
        assertTrue((result as MtgPoolResolver.PoolResult.Failed).message.contains("no saved cube named"))
    }

    @Test
    fun `a saved cube with no parseable cards fails as empty`() {
        every { cubeListService.get(discordId, "Empty") } returns savedCube("Empty", "# just a comment\n\n")

        val result = resolve("Empty", null)

        assertTrue(result is MtgPoolResolver.PoolResult.Failed)
        assertTrue((result as MtgPoolResolver.PoolResult.Failed).message.contains("empty"))
    }

    @Test
    fun `a Scryfall failure resolving a saved cube surfaces the failure message`() {
        every { cubeListService.get(discordId, "Cube") } returns savedCube("Cube", "Lightning Bolt")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Failure("Scryfall is down.")

        val result = resolve("Cube", null)

        assertEquals("Scryfall is down.", (result as MtgPoolResolver.PoolResult.Failed).message)
    }

    @Test
    fun `a saved cube whose every card is unknown fails rather than returning an empty pool`() {
        every { cubeListService.get(discordId, "Cube") } returns savedCube("Cube", "Notacard")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(emptyList())

        val result = resolve("Cube", null)

        assertTrue((result as MtgPoolResolver.PoolResult.Failed).message.contains("matched Scryfall"))
    }

    @Test
    fun `a capped saved-cube resolution carries a note`() {
        every { cubeListService.get(discordId, "Cube") } returns savedCube("Cube", "Lightning Bolt")
        coEvery { fetcher.fetchByNames(any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(card("Lightning Bolt", MtgColor.RED)), capped = true)

        val result = resolve("Cube", null) as MtgPoolResolver.PoolResult.Ready

        assertTrue(result.note!!.contains("${ScryfallCubeFetcher.DEFAULT_MAX_CARDS}"))
    }

    @Test
    fun `a saved cube name takes precedence over a query`() {
        every { cubeListService.get(discordId, "Cube") } returns savedCube("Cube", "Lightning Bolt")
        coEvery { fetcher.fetchByNames(any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(card("Lightning Bolt", MtgColor.RED)))

        val result = resolve("Cube", "set:vow") as MtgPoolResolver.PoolResult.Ready

        assertEquals("saved cube \"Cube\"", result.label)
        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    // --- query path -----------------------------------------------------

    @Test
    fun `a query resolves through Scryfall search with the query as label`() {
        coEvery { fetcher.fetch(any(), any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(card("Lightning Bolt", MtgColor.RED)))

        val result = resolve(null, "set:vow") as MtgPoolResolver.PoolResult.Ready

        assertEquals(1, result.pool.size)
        assertEquals("set:vow", result.label)
        assertNull(result.note)
    }

    @Test
    fun `a capped query resolution carries a note`() {
        coEvery { fetcher.fetch(any(), any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(card("Lightning Bolt", MtgColor.RED)), capped = true)

        val result = resolve(null, "t:creature") as MtgPoolResolver.PoolResult.Ready

        assertTrue(result.note!!.contains("${ScryfallCubeFetcher.DEFAULT_MAX_CARDS}"))
    }

    @Test
    fun `a Scryfall query failure surfaces the failure message`() {
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Failure("No cards matched.")

        val result = resolve(null, "asdfqwer")

        assertEquals("No cards matched.", (result as MtgPoolResolver.PoolResult.Failed).message)
    }

    @Test
    fun `neither a saved name nor a query fails asking for input`() {
        assertTrue(resolve(null, null) is MtgPoolResolver.PoolResult.Failed)
        // Blank/whitespace counts as absent.
        assertTrue(resolve("  ", "  ") is MtgPoolResolver.PoolResult.Failed)
    }

    // --- capNote --------------------------------------------------------

    @Test
    fun `capNote is null when not capped and explains the cap when capped`() {
        assertNull(resolver.capNote(false))
        assertTrue(resolver.capNote(true)!!.contains("${ScryfallCubeFetcher.DEFAULT_MAX_CARDS}"))
    }
}
