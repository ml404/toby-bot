package bot.toby.command.commands.mtg

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.mtg.CardCombos
import common.mtg.CardRulings
import common.mtg.CubeCard
import common.mtg.MtgColor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CardCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var command: CardCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        command = CardCommand(fetcher, Dispatchers.Unconfined)
    }

    private fun strOpt(value: String): OptionMapping = mockk { every { asString } returns value }
    private fun run() = command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay = 0)

    @Test
    fun `lookup replies with a card panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_LOOKUP
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("Lightning Bolt")
        coEvery { fetcher.fetchByNames(listOf("Lightning Bolt")) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Lightning Bolt", setOf(MtgColor.RED), typeLine = "Instant", manaValue = 1.0, rarity = "common")),
        )

        run()

        assertEquals("Lightning Bolt", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Instant"))
    }

    @Test
    fun `lookup reports when the name doesn't resolve`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_LOOKUP
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("Notacard")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Failure("none")

        run()

        assertTrue(slot.captured.description!!.contains("Notacard"))
    }

    @Test
    fun `rulings replies with a rulings panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_RULINGS
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("Doubling Season")
        coEvery { fetcher.fetchRulings("Doubling Season") } returns CardRulings(
            "Doubling Season", "https://scryfall.com/card",
            listOf(CardRulings.Ruling("2021-03-19", "Tokens are doubled.")),
        )

        run()

        assertEquals("Doubling Season — rulings", slot.captured.title)
        assertTrue(slot.captured.description!!.contains("Tokens are doubled."))
    }

    @Test
    fun `combos replies with a combos panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_COMBOS
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("Kiki-Jiki")
        coEvery { fetcher.fetchCombos("Kiki-Jiki") } returns CardCombos(
            "Kiki-Jiki, Mirror Breaker",
            listOf(CardCombos.Combo("7", listOf("Kiki-Jiki", "Zealous Conscripts"), listOf("Infinite haste"), "u")),
        )

        run()

        assertEquals("Kiki-Jiki, Mirror Breaker — combos", slot.captured.title)
        assertTrue(slot.captured.fields.any { it.value!!.contains("Infinite haste") })
    }

    @Test
    fun `combos reports when Commander Spellbook is unreachable`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_COMBOS
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("Kiki-Jiki")
        coEvery { fetcher.fetchCombos(any()) } returns null

        run()

        assertTrue(slot.captured.description!!.contains("Commander Spellbook"))
    }

    @Test
    fun `search with one result replies with a single card panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_SEARCH
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("iron man")
        every { event.getOption(CardCommand.OPT_TYPE) } returns null
        every { event.getOption(CardCommand.OPT_QUERY) } returns null
        coEvery { fetcher.fetch("\"iron man\"", CardCommand.SEARCH_MAX) } returns ScryfallCubeFetcher.Result.Success(
            listOf(CubeCard("Iron Man, Tony Stark", setOf(MtgColor.RED), typeLine = "Legendary Artifact Creature")),
        )

        run()

        assertEquals("Iron Man, Tony Stark", slot.captured.title)
    }

    @Test
    fun `search with multiple results sends a paginated browser`() {
        val content = slot<String>()
        every { event.hook.sendMessage(capture(content)) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_SEARCH
        every { event.getOption(CardCommand.OPT_NAME) } returns strOpt("iron man")
        every { event.getOption(CardCommand.OPT_TYPE) } returns strOpt("creature")
        every { event.getOption(CardCommand.OPT_QUERY) } returns null
        coEvery { fetcher.fetch("\"iron man\" t:creature", CardCommand.SEARCH_MAX) } returns
            ScryfallCubeFetcher.Result.Success(
                listOf(
                    CubeCard("Iron Man, Armored Avenger", setOf(MtgColor.WHITE), typeLine = "Legendary Artifact Creature"),
                    CubeCard("Iron Man, Bleeding Edge", setOf(MtgColor.BLUE), typeLine = "Legendary Artifact Creature"),
                ),
            )

        run()

        assertTrue(content.captured.contains("Found **2**"))
        assertTrue(content.captured.contains("iron man"))
    }

    @Test
    fun `search needs at least one filter`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CardCommand.SUB_SEARCH
        every { event.getOption(CardCommand.OPT_NAME) } returns null
        every { event.getOption(CardCommand.OPT_TYPE) } returns null
        every { event.getOption(CardCommand.OPT_QUERY) } returns null

        run()

        assertTrue(slot.captured.description!!.contains("search"))
    }

    @Test
    fun `buildSearchQuery composes name, type and raw query`() {
        assertEquals(
            "\"iron man\" t:legendary t:creature c:r",
            CardCommand.buildSearchQuery("iron man", "legendary creature", "c:r"),
        )
        assertEquals("t:dragon", CardCommand.buildSearchQuery(null, "dragon", null))
        assertEquals("", CardCommand.buildSearchQuery(" ", null, ""))
    }

    @Test
    fun `search button id round-trips query and index through base64`() {
        val id = CardCommand.encodeSearchButton("\"iron man\" t:creature", 3)
        assertTrue(id.startsWith("${CardCommand.SEARCH_BUTTON}:3:"))
        val decoded = CardCommand.decodeSearchButton(id)
        assertEquals("\"iron man\" t:creature", decoded?.query)
        assertEquals(3, decoded?.index)
        // A foreign id isn't mistaken for ours.
        assertEquals(null, CardCommand.decodeSearchButton("excuse-page:approved:1:2:"))
    }

    @Test
    fun `exposes search, lookup, rulings and combos subcommands`() {
        assertEquals("mtgcard", command.name)
        assertEquals(setOf("search", "lookup", "rulings", "combos"), command.subCommands.map { it.name }.toSet())
        // The single-card subcommands require a name; search's filters are all optional.
        command.subCommands.filter { it.name != "search" }.forEach { sub ->
            assertTrue(sub.options.first { it.name == "name" }.isRequired)
        }
        assertTrue(command.subCommands.first { it.name == "search" }.options.none { it.isRequired })
    }
}
