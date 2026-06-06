package bot.toby.command.commands.mtg

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.mtg.CubeCard
import common.mtg.MtgColor
import database.dto.user.CubeListDto
import database.service.user.CubeListService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.utils.FileUpload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class CubeCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var cubeListService: CubeListService
    private lateinit var configService: database.service.guild.ConfigService
    private lateinit var command: CubeCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        cubeListService = mockk(relaxed = true)
        configService = mockk(relaxed = true) // null config → USD default
        // A real resolver over the mocked fetcher, so the fetch-based mocking
        // below drives pool resolution exactly as in production.
        val resolver = MtgPoolResolver(fetcher, cubeListService)
        command = CubeCommand(resolver, cubeListService, configService, Dispatchers.Unconfined)
        every { requestingUserDto.discordId } returns 100L
        every { webhookMessageCreateAction.addFiles(any<FileUpload>()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.queue() } just runs
    }

    private fun intOpt(value: Int): OptionMapping = mockk { every { asInt } returns value }
    private fun strOpt(value: String): OptionMapping = mockk { every { asString } returns value }
    private fun boolOpt(value: Boolean): OptionMapping = mockk { every { asBoolean } returns value }

    private fun run() = command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay = 0)

    private fun pool(n: Int): List<CubeCard> =
        (1..n).map { CubeCard("Card $it", colors = setOf(MtgColor.entries[it % 5])) }

    private fun savedCube(name: String, cards: String) =
        CubeListDto(discordId = 100L, name = name, cards = cards, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    // --- asfan ---------------------------------------------------------

    @Test
    fun `asfan computes the doc's worked example`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_ASFAN
        every { event.getOption(CubeCommand.OPT_TOTAL) } returns intOpt(60)
        every { event.getOption(CubeCommand.OPT_CUBE_SIZE) } returns intOpt(540)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null // → default 15

        run()

        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
        assertEquals("As-fan", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("1.67"), "description was: ${slot.captured.description}")
    }

    @Test
    fun `asfan with an invalid cube size returns an error embed`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_ASFAN
        every { event.getOption(CubeCommand.OPT_TOTAL) } returns intOpt(10)
        every { event.getOption(CubeCommand.OPT_CUBE_SIZE) } returns intOpt(0) // invalid
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    // --- preview -------------------------------------------------------

    @Test
    fun `preview shows the as-fan distribution of the fetched pool`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:vow")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(50))

        run()

        assertEquals("Cube preview", slot.captured.title)
        assertTrue(slot.captured.fields.any { it.name == "Card types" })
        coVerify(exactly = 1) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `preview reports the cube value in the guild's configured currency`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:vow")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { configService.getConfigByName("CUBE_CURRENCY", "1") } returns mockk { every { value } returns "eur" }
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(
                CubeCard("Bolt", setOf(MtgColor.RED), priceUsd = "2.00", priceEur = "1.50"),
                CubeCard("Bear", setOf(MtgColor.GREEN), priceUsd = "0.50", priceEur = "0.40"),
            ),
        )

        run()

        val value = slot.captured.fields.first { it.name == "Cube value" }.value!!
        assertTrue(value.contains("€1.90"), "expected EUR total, got: $value")
    }

    @Test
    fun `preview reports the most and least valuable cards`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:vow")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(
                CubeCard("Pricey", setOf(MtgColor.RED), priceUsd = "60.00"),
                CubeCard("Cheap", setOf(MtgColor.BLUE), priceUsd = "0.25"),
            ),
        )

        run()

        val value = slot.captured.fields.first { it.name == "Top & bottom value" }.value!!
        assertTrue(value.contains("Pricey ($60.00)"), value)
        assertTrue(value.contains("Cheap ($0.25)"), value)
    }

    @Test
    fun `preview surfaces a fetch failure as an error embed`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:zzz")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Failure("No cards matched.")

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    @Test
    fun `preview surfaces the capped-pool note when the query overflows`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("t:creature")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(50), capped = true)

        run()

        assertTrue(slot.captured.description!!.contains("only the first 750"), "note was: ${slot.captured.description}")
    }

    @Test
    fun `an unexpected failure resolves the interaction with an error embed`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:vow")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        coEvery { fetcher.fetch(any(), any()) } throws RuntimeException("scryfall exploded")

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Something went wrong"))
    }

    // --- generate ------------------------------------------------------

    @Test
    fun `generate deals packs and attaches the pack list as a file`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("cube:vintage")
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(2)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(3)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns boolOpt(true)
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(20))

        run()

        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
        assertTrue(slot.captured.title!!.contains("Generated 2 packs of 3"))
    }

    @Test
    fun `generate reports the packs value in the guild's configured currency`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("cube:vintage")
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(2)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns boolOpt(false)
        every { configService.getConfigByName("CUBE_CURRENCY", "1") } returns mockk { every { value } returns "eur" }
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(
                CubeCard("Bolt", setOf(MtgColor.RED), priceUsd = "2.00", priceEur = "1.50"),
                CubeCard("Bear", setOf(MtgColor.GREEN), priceUsd = "0.50", priceEur = "0.40"),
            ),
        )

        run()

        val value = slot.captured.fields.first { it.name == "Packs value" }.value!!
        assertTrue(value.contains("€1.90"), "expected EUR packs value, got: $value")
    }

    @Test
    fun `generate reports when the pool is too small`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("t:angel")
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(24)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(15)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(5))

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
        verify(exactly = 0) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
    }

    @Test
    fun `generate surfaces a fetch failure`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("garbage")
        every { event.getOption(CubeCommand.OPT_PACKS) } returns null
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Failure("Scryfall returned HTTP 500.")

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    // --- saved cubes ---------------------------------------------------

    @Test
    fun `generate from a saved cube resolves the names and deals packs`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(3)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        every { cubeListService.get(100L, "My Cube") } returns savedCube("My Cube", "3 Bolt\nForest")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Bolt", setOf(MtgColor.RED)), CubeCard("Forest", isLand = true)),
        )

        run()

        verify(exactly = 1) { cubeListService.get(100L, "My Cube") }
        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
        assertTrue(slot.captured.title!!.contains("Generated 1 packs of 3"))
        assertTrue(slot.captured.description!!.contains("My Cube"))
    }

    @Test
    fun `a saved cube with full DFC names requests Scryfall by front face`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        every { cubeListService.get(100L, "My Cube") } returns
            savedCube("My Cube", "Archangel Avacyn // Avacyn, the Purifier")
        val requested = slot<List<String>>()
        coEvery { fetcher.fetchByNames(capture(requested)) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Archangel Avacyn // Avacyn, the Purifier", setOf(MtgColor.WHITE))),
        )

        run()

        assertEquals(listOf("Archangel Avacyn"), requested.captured)
        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
    }

    @Test
    fun `generate from a saved cube reports cards it could not resolve`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        every { cubeListService.get(100L, "My Cube") } returns savedCube("My Cube", "Bolt\nNonexistent Card")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Bolt", setOf(MtgColor.RED))),
        )

        run()

        val warning = slot.captured.fields.firstOrNull { it.name?.contains("Couldn't find") == true }
        assertTrue(warning != null, "expected a 'couldn't find' field on the embed")
        assertTrue(warning!!.value!!.contains("Nonexistent Card"))
    }

    @Test
    fun `preview from a saved cube shows its distribution`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { cubeListService.get(100L, "My Cube") } returns savedCube("My Cube", "Bolt\nForest")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Bolt", setOf(MtgColor.RED)), CubeCard("Forest", isLand = true)),
        )

        run()

        assertEquals("Cube preview", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("My Cube"))
    }

    @Test
    fun `generate with an unknown saved cube name errors`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("Nope")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns null
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        every { cubeListService.get(100L, "Nope") } returns null

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
        coVerify(exactly = 0) { fetcher.fetchByNames(any()) }
    }

    @Test
    fun `generate with neither query nor saved errors`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns null
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns null
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    @Test
    fun `lists the user's saved cubes with their card counts`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_SAVED
        every { cubeListService.listForUser(100L) } returns listOf(
            savedCube("Vintage", "3 Bolt\nForest"),
            savedCube("Pauper", "Lightning Bolt"),
        )

        run()

        verify(exactly = 1) { cubeListService.listForUser(100L) }
        assertEquals("Your saved cubes", slot.captured.title)
        val desc = slot.captured.description!!
        assertTrue(desc.contains("Vintage"), "description was: $desc")
        assertTrue(desc.contains("4 cards"), "description was: $desc")
        assertTrue(desc.contains("Pauper"))
        assertTrue(desc.contains("1 card") && !desc.contains("1 cards"), "singular card count: $desc")
    }

    @Test
    fun `unknown subcommand replies with guidance`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns null

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    @Test
    fun `exposes the four cube subcommands with their options`() {
        assertEquals("mtgcube", command.name)
        val subs = command.subCommands.associateBy { it.name }
        assertEquals(setOf("asfan", "preview", "generate", "saved"), subs.keys)

        val asfan = subs.getValue("asfan").options
        assertEquals(OptionType.INTEGER, asfan.first { it.name == "total" }.type)
        assertTrue(asfan.first { it.name == "total" }.isRequired)
        assertTrue(asfan.first { it.name == "cube-size" }.isRequired)
        assertEquals(false, asfan.first { it.name == "pack-size" }.isRequired)

        val generate = subs.getValue("generate").options
        assertEquals(OptionType.STRING, generate.first { it.name == "query" }.type)
        assertEquals(OptionType.BOOLEAN, generate.first { it.name == "balanced" }.type)
        assertEquals(false, generate.first { it.name == "query" }.isRequired)
        assertEquals(false, generate.first { it.name == "saved" }.isRequired)
    }
}
