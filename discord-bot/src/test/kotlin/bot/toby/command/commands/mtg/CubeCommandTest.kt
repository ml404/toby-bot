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
        // Unconfined so the launched IO coroutine resolves synchronously in tests.
        command = CubeCommand(fetcher, cubeListService, configService, Dispatchers.Unconfined)
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
        val embed = slot.captured
        assertEquals("As-fan", embed.title)
        // (60 / 540) × 15 = 1.67
        assertTrue(embed.description!!.contains("1.67"), "description was: ${embed.description}")
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
        // The cube report is wired in: the analytics fields ride along.
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
        // Guild has CUBE_CURRENCY = eur (guild id "1" from CommandTest mocks).
        every {
            configService.getConfigByName("CUBE_CURRENCY", "1")
        } returns mockk { every { value } returns "eur" }
        val priced = listOf(
            CubeCard("Bolt", setOf(MtgColor.RED), priceUsd = "2.00", priceEur = "1.50"),
            CubeCard("Bear", setOf(MtgColor.GREEN), priceUsd = "0.50", priceEur = "0.40"),
        )
        coEvery { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(priced)

        run()

        val value = slot.captured.fields.first { it.name == "Cube value" }.value!!
        assertTrue(value.contains("€1.90"), "expected EUR total, got: $value")
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

    // --- saved cubes ---------------------------------------------------

    private fun savedCube(name: String, cards: String) =
        CubeListDto(discordId = 100L, name = name, cards = cards, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

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
    fun `a saved cube's front-face name resolves a transform card`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        // The user pasted only the front face; Scryfall returns the full name.
        every { cubeListService.get(100L, "My Cube") } returns savedCube("My Cube", "Archangel Avacyn")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Archangel Avacyn // Avacyn, the Purifier", setOf(MtgColor.WHITE))),
        )

        run()

        // Resolved (not "none matched"): a pack is dealt and attached.
        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
        assertTrue(slot.captured.title!!.contains("Generated 1 packs of 1"))
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
        // The cube stores the full "//" name (as exported from a deckbuilder).
        every { cubeListService.get(100L, "My Cube") } returns
            savedCube("My Cube", "Archangel Avacyn // Avacyn, the Purifier")
        val requested = slot<List<String>>()
        coEvery { fetcher.fetchByNames(capture(requested)) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Archangel Avacyn // Avacyn, the Purifier", setOf(MtgColor.WHITE))),
        )

        run()

        // Scryfall is asked for the front face only (the full "//" name 404s).
        assertEquals(listOf("Archangel Avacyn"), requested.captured)
        // …and the returned full-name card still resolves into a dealt pack.
        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
    }

    @Test
    fun `a saved cube's back-face name also resolves a transform card`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_GENERATE
        every { event.getOption(CubeCommand.OPT_SAVED) } returns strOpt("My Cube")
        every { event.getOption(CubeCommand.OPT_QUERY) } returns null
        every { event.getOption(CubeCommand.OPT_PACKS) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns intOpt(1)
        every { event.getOption(CubeCommand.OPT_BALANCED) } returns null
        every { cubeListService.get(100L, "My Cube") } returns savedCube("My Cube", "Avacyn, the Purifier")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Archangel Avacyn // Avacyn, the Purifier", setOf(MtgColor.WHITE))),
        )

        run()

        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
        assertTrue(slot.captured.title!!.contains("Generated 1 packs of 1"))
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

    // --- metadata ------------------------------------------------------

    @Test
    fun `unknown subcommand replies with guidance`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns null

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
        // 3 Bolt + 1 Forest counts copies, not lines.
        assertTrue(desc.contains("4 cards"), "description was: $desc")
        assertTrue(desc.contains("Pauper"))
        assertTrue(desc.contains("1 card") && !desc.contains("1 cards"), "singular card count: $desc")
    }

    @Test
    fun `saved with no saved cubes nudges the user to the website`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_SAVED
        every { cubeListService.listForUser(100L) } returns emptyList()

        run()

        assertEquals("Your saved cubes", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("haven't saved any"))
    }

    // --- card lookup ---------------------------------------------------

    @Test
    fun `card looks the name up and replies with a card panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_CARD
        every { event.getOption(CubeCommand.OPT_NAME) } returns strOpt("Lightning Bolt")
        coEvery { fetcher.fetchByNames(listOf("Lightning Bolt")) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Lightning Bolt", setOf(MtgColor.RED), typeLine = "Instant", manaValue = 1.0, rarity = "common")),
        )

        run()

        assertEquals("Lightning Bolt", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Instant"))
    }

    @Test
    fun `card reports when the name doesn't resolve`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_CARD
        every { event.getOption(CubeCommand.OPT_NAME) } returns strOpt("Notacard")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Failure("none")

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Notacard"))
    }

    @Test
    fun `rulings looks the name up and replies with a rulings panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_RULINGS
        every { event.getOption(CubeCommand.OPT_NAME) } returns strOpt("Doubling Season")
        coEvery { fetcher.fetchRulings("Doubling Season") } returns common.mtg.CardRulings(
            "Doubling Season", "https://scryfall.com/card",
            listOf(common.mtg.CardRulings.Ruling("2021-03-19", "Tokens are doubled.")),
        )

        run()

        assertEquals("Doubling Season — rulings", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Tokens are doubled."))
    }

    @Test
    fun `rulings reports when the name doesn't resolve`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_RULINGS
        every { event.getOption(CubeCommand.OPT_NAME) } returns strOpt("Notacard")
        coEvery { fetcher.fetchRulings(any()) } returns null

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Notacard"))
    }

    @Test
    fun `exposes the six subcommands with their options`() {
        assertEquals("cube", command.name)
        val subs = command.subCommands.associateBy { it.name }
        assertEquals(setOf("asfan", "preview", "generate", "saved", "card", "rulings"), subs.keys)
        assertEquals(OptionType.STRING, subs.getValue("card").options.first { it.name == "name" }.type)
        assertTrue(subs.getValue("card").options.first { it.name == "name" }.isRequired)
        assertTrue(subs.getValue("rulings").options.first { it.name == "name" }.isRequired)

        val asfan = subs.getValue("asfan").options
        assertEquals(OptionType.INTEGER, asfan.first { it.name == "total" }.type)
        assertTrue(asfan.first { it.name == "total" }.isRequired)
        assertTrue(asfan.first { it.name == "cube-size" }.isRequired)
        assertEquals(false, asfan.first { it.name == "pack-size" }.isRequired)

        val generate = subs.getValue("generate").options
        assertEquals(OptionType.STRING, generate.first { it.name == "query" }.type)
        assertEquals(OptionType.BOOLEAN, generate.first { it.name == "balanced" }.type)
        // query and saved are both optional (one-of, validated at runtime).
        assertEquals(false, generate.first { it.name == "query" }.isRequired)
        assertEquals(false, generate.first { it.name == "saved" }.isRequired)
    }
}
