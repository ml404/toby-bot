package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.player.PlayCommand
import core.command.Command
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class HelpCommandTest : CommandTest {
    private lateinit var helpCommand: HelpCommand
    lateinit var commands: List<Command>

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        commands = listOf(PlayCommand())
        helpCommand = HelpCommand(commands)
    }

    @Test
    fun testHandleCommandWithNoArgs() {
        // No argument → an in-Discord overview embed (not an external link).
        every { event.options } returns emptyList()
        every { event.getOption("command") } returns null

        val embedSlot = slot<MessageEmbed>()
        every { event.hook.sendMessageEmbeds(capture(embedSlot), *anyVararg()) } returns
            CommandTest.webhookMessageCreateAction

        helpCommand.handle(DefaultCommandContext(event), mockk(), 0)

        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
        // The overview must hand a brand-new user a zero-setup first action
        // and group PlayCommand under Music.
        val embed = embedSlot.captured
        assertTrue(embed.description!!.contains("/blackjack solo"))
        assertTrue(embed.fields.any { it.name!!.contains("Music") && it.value!!.contains("/play") })
    }

    @Test
    fun testHandleCommandWithCommandArg() {
        // Mock user interaction with a command argument
        val optionMapping: OptionMapping = mockk()
        every { event.options } returns listOf(optionMapping)
        every { event.getOption("command") } returns optionMapping
        every { optionMapping.asString } returns "play"

        helpCommand.handle(DefaultCommandContext(event), mockk(), 0)

        // Detail lookups now reply with a rich, ephemeral usage embed
        // (title + arguments) rather than a plain one-line string.
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }
}
