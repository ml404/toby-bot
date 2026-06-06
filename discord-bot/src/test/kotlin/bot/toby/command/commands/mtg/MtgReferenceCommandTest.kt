package bot.toby.command.commands.mtg

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import common.mtg.MtgSet
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

class MtgReferenceCommandTest : CommandTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var command: MtgReferenceCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        fetcher = mockk()
        command = MtgReferenceCommand(fetcher, Dispatchers.Unconfined)
    }

    private fun strOpt(value: String): OptionMapping = mockk { every { asString } returns value }
    private fun run() = command.handle(DefaultCommandContext(event), requestingUserDto, deleteDelay = 0)

    @Test
    fun `set looks up a set by code and replies with a set panel`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns MtgReferenceCommand.SUB_SET
        every { event.getOption(MtgReferenceCommand.OPT_CODE) } returns strOpt("vow")
        coEvery { fetcher.fetchSet("vow") } returns MtgSet("VOW", "Innistrad: Crimson Vow", "expansion", "2021-11-19", 277, null, null)

        run()

        assertEquals("Innistrad: Crimson Vow (VOW)", slot.captured.title)
    }

    @Test
    fun `set reports an unknown code`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns MtgReferenceCommand.SUB_SET
        every { event.getOption(MtgReferenceCommand.OPT_CODE) } returns strOpt("zzz")
        coEvery { fetcher.fetchSet(any()) } returns null

        run()

        assertTrue(slot.captured.description!!.contains("zzz"))
    }

    @Test
    fun `rule looks up a keyword in the glossary, no network`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns MtgReferenceCommand.SUB_RULE
        every { event.getOption(MtgReferenceCommand.OPT_TERM) } returns strOpt("trample")

        run()

        assertEquals("Trample", slot.captured.title)
    }

    @Test
    fun `rule reports an unknown keyword`() {
        val slot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(slot), *anyVararg()) } returns webhookMessageCreateAction
        every { event.subcommandName } returns MtgReferenceCommand.SUB_RULE
        every { event.getOption(MtgReferenceCommand.OPT_TERM) } returns strOpt("zzznotaword")

        run()

        assertEquals("Couldn't build that cube", slot.captured.title)
    }

    @Test
    fun `exposes set and rule subcommands`() {
        assertEquals("mtg", command.name)
        assertEquals(setOf("set", "rule"), command.subCommands.map { it.name }.toSet())
    }
}
