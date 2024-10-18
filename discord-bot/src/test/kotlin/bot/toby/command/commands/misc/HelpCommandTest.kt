package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import bot.toby.command.commands.music.player.PlayCommand
import core.command.Command
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
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
        // Mock user interaction with no arguments

        // Mock the event's options to be empty
        every { event.options } returns emptyList()
        every { event.getOption("command") } returns null

        // Test handle method
        helpCommand.handle(DefaultCommandContext(event), mockk(), 0)

        // Verify interactions
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun testHandleCommandWithCommandArg() {
        // Mock user interaction with a command argument

        // Mock the event's options to include a command argument
        val optionMapping: OptionMapping = mockk()
        every { event.options } returns listOf(optionMapping)

        // Mock the CommandManager's command
        val musicCommand: Command = mockk<PlayCommand>()
        every { event.getOption("command") } returns optionMapping
        every { optionMapping.asString } returns "play"
        every { musicCommand.description } returns ""
        every { musicCommand.name } returns "play"

        // Test handle method
        helpCommand.handle(DefaultCommandContext(event), mockk(), 0)

        // Verify interactions
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
}
