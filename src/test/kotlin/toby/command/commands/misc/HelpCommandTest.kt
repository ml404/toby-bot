package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.ICommand
import toby.command.commands.music.PlayCommand
import toby.managers.CommandManager

internal class HelpCommandTest : CommandTest {
    private lateinit var commandManager: CommandManager
    private lateinit var helpCommand: HelpCommand

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        commandManager = mockk()
        helpCommand = HelpCommand(commandManager)
    }

    @Test
    fun testHandleCommandWithNoArgs() {
        // Mock user interaction with no arguments

        // Mock the event's options to be empty
        every { event.options } returns emptyList()
        every { event.getOption("command") } returns null

        // Mock the CommandManager's commands
        val musicCommand: ICommand = mockk()
        val miscCommand: ICommand = mockk()

        every { commandManager.musicCommands } returns listOf(musicCommand)
        every { commandManager.miscCommands } returns listOf(miscCommand)
        every { musicCommand.name } returns "musicName"
        every { miscCommand.name } returns "miscName"

        // Test handle method
        helpCommand.handle(CommandContext(event), mockk(), 0)

        // Verify interactions
        verify(exactly = 1) { event.hook.sendMessageFormat(any()) }
    }

    @Test
    fun testHandleCommandWithCommandArg() {
        // Mock user interaction with a command argument

        // Mock the event's options to include a command argument
        val optionMapping: OptionMapping = mockk()
        every { event.options } returns listOf(optionMapping)

        // Mock the CommandManager's command
        val musicCommand: ICommand = mockk<PlayCommand>()
        every { commandManager.getCommand(any()) } returns musicCommand
        every { event.getOption("command") } returns optionMapping
        every { optionMapping.asString } returns "play"
        every { musicCommand.description } returns ""
        every { musicCommand.name } returns "play"

        // Test handle method
        helpCommand.handle(CommandContext(event), mockk(), 0)

        // Verify interactions
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
}
