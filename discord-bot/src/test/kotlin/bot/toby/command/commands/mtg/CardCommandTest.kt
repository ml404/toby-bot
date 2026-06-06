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
    fun `exposes lookup, rulings and combos subcommands`() {
        assertEquals("card", command.name)
        assertEquals(setOf("lookup", "rulings", "combos"), command.subCommands.map { it.name }.toSet())
        command.subCommands.forEach { sub ->
            assertTrue(sub.options.first { it.name == "name" }.isRequired)
        }
    }
}
