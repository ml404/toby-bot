package bot.toby.command.commands.mtg

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.mtg.CubeCard
import common.mtg.MtgColor
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.utils.FileUpload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CubeCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var command: CubeCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        command = CubeCommand(fetcher)
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
        every { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(50))

        run()

        assertEquals("Cube preview", slot.captured.title)
        verify(exactly = 1) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `preview surfaces a fetch failure as an error embed`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns CubeCommand.SUB_PREVIEW
        every { event.getOption(CubeCommand.OPT_QUERY) } returns strOpt("set:zzz")
        every { event.getOption(CubeCommand.OPT_PACK_SIZE) } returns null
        every { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Failure("No cards matched.")

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
        every { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(20))

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
        every { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Success(pool(5))

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
        every { fetcher.fetch(any(), any()) } returns ScryfallCubeFetcher.Result.Failure("Scryfall returned HTTP 500.")

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
    fun `exposes the three subcommands with their options`() {
        assertEquals("cube", command.name)
        val subs = command.subCommands.associateBy { it.name }
        assertEquals(setOf("asfan", "preview", "generate"), subs.keys)

        val asfan = subs.getValue("asfan").options
        assertEquals(OptionType.INTEGER, asfan.first { it.name == "total" }.type)
        assertTrue(asfan.first { it.name == "total" }.isRequired)
        assertTrue(asfan.first { it.name == "cube-size" }.isRequired)
        assertEquals(false, asfan.first { it.name == "pack-size" }.isRequired)

        val generate = subs.getValue("generate").options
        assertEquals(OptionType.STRING, generate.first { it.name == "query" }.type)
        assertEquals(OptionType.BOOLEAN, generate.first { it.name == "balanced" }.type)
    }
}
