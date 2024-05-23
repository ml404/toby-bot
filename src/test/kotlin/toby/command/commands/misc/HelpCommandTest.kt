package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.ICommand
import toby.command.commands.music.IMusicCommand
import toby.command.commands.music.PlayCommand
import toby.jpa.dto.UserDto
import toby.managers.CommandManager

internal class HelpCommandTest : CommandTest {
    @Mock
    private var commandManager: CommandManager? = null

    private var helpCommand: HelpCommand? = null


    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        commandManager = Mockito.mock(CommandManager::class.java)

        `when`(CommandTest.event.hook).thenReturn(CommandTest.interactionHook)
        helpCommand = HelpCommand(commandManager!!)
    }

    @Test
    fun testHandleCommandWithNoArgs() {
        // Mock user interaction with no arguments

        // Mock the event's options to be empty

        `when`(CommandTest.event.options).thenReturn(listOf())

        // Mock the CommandManager's commands
        val musicCommand: ICommand = Mockito.mock(IMusicCommand::class.java)
        val miscCommand: ICommand = Mockito.mock(IMiscCommand::class.java)

        `when`(commandManager!!.musicCommands).thenReturn(listOf(musicCommand))
        `when`(commandManager!!.miscCommands).thenReturn(listOf(miscCommand))

        // Test handle method
        helpCommand!!.handle(CommandContext(CommandTest.event), Mockito.mock(UserDto::class.java), 0)

        // Verify interactions
        //if we pass no args, a big list of commands are printed out in a formatted string
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(ArgumentMatchers.anyString())
    }

    @Test
    fun testHandleCommandWithCommandArg() {
        // Mock user interaction with a command argument

        // Mock the event's options to include a command argument

        val optionMapping = Mockito.mock(OptionMapping::class.java)
        `when`(CommandTest.event.options)
            .thenReturn(listOf(optionMapping)) // Add an option to simulate a command argument

        // Mock the CommandManager's command
        val musicCommand: ICommand = Mockito.mock(PlayCommand::class.java)
        `when`(commandManager!!.getCommand(ArgumentMatchers.anyString())).thenReturn(musicCommand)
        `when`(CommandTest.event.getOption("command")).thenReturn(optionMapping)
        `when`(optionMapping.asString).thenReturn("play")
        `when`(musicCommand.description).thenReturn("")
        // Test handle method
        helpCommand!!.handle(CommandContext(CommandTest.event), Mockito.mock(UserDto::class.java), 0)

        // Verify interactions
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessage(ArgumentMatchers.anyString())
    }
}