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
import java.time.Instant

class DeckCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var cubeListService: CubeListService
    private lateinit var command: DeckCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        cubeListService = mockk(relaxed = true)
        command = DeckCommand(MtgPoolResolver(fetcher, cubeListService), Dispatchers.Unconfined)
        every { requestingUserDto.discordId } returns 100L
    }

    private fun strOpt(value: String): OptionMapping = mockk { every { asString } returns value }
    private fun run() = command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay = 0)
    private fun savedCube(name: String, cards: String) =
        CubeListDto(discordId = 100L, name = name, cards = cards, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test
    fun `legality checks a saved deck against the chosen format and flags banned cards`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns DeckCommand.SUB_LEGALITY
        every { event.getOption(DeckCommand.OPT_FORMAT) } returns strOpt("modern")
        every { event.getOption(DeckCommand.OPT_SAVED) } returns strOpt("My Deck")
        every { event.getOption(DeckCommand.OPT_QUERY) } returns null
        every { cubeListService.get(100L, "My Deck") } returns savedCube("My Deck", "Lightning Bolt\nLurrus")
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Success(
            listOf(
                CubeCard("Lightning Bolt", setOf(MtgColor.RED), legalities = mapOf("modern" to "legal")),
                CubeCard("Lurrus", legalities = mapOf("modern" to "banned")),
            ),
        )

        run()

        assertTrue(slot.captured.title!!.contains("Not Modern-legal"))
        assertTrue(slot.captured.fields.any { it.name!!.contains("Banned") && it.value!!.contains("Lurrus") })
    }

    @Test
    fun `legality without a format returns an error`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns DeckCommand.SUB_LEGALITY
        every { event.getOption(DeckCommand.OPT_FORMAT) } returns null
        every { event.getOption(DeckCommand.OPT_SAVED) } returns strOpt("My Deck")
        every { event.getOption(DeckCommand.OPT_QUERY) } returns null

        run()

        assertTrue(slot.captured.description!!.contains("format"))
    }

    @Test
    fun `exposes the legality subcommand with format choices`() {
        assertEquals("deck", command.name)
        assertEquals(setOf("legality"), command.subCommands.map { it.name }.toSet())
        val format = command.subCommands.first().options.first { it.name == "format" }
        assertTrue(format.isRequired)
        assertEquals(common.mtg.CubeCard.FORMATS.size, format.choices.size)
    }
}
