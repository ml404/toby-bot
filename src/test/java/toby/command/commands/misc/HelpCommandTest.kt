package toby.command.commands.misc;

import toby.command.CommandTest;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.command.commands.music.IMusicCommand;
import toby.command.commands.music.PlayCommand;
import toby.jpa.dto.UserDto;
import toby.managers.CommandManager;

import java.util.List;

import static org.mockito.Mockito.*;

class HelpCommandTest implements CommandTest {

    @Mock
    private CommandManager commandManager;

    private HelpCommand helpCommand;


    @BeforeEach
    public void setup() {
        setUpCommonMocks();
        commandManager = mock(CommandManager.class);

        when(event.getHook()).thenReturn(interactionHook);
        helpCommand = new HelpCommand(commandManager);
    }

    @Test
    public void testHandleCommandWithNoArgs() {
        // Mock user interaction with no arguments

        // Mock the event's options to be empty
        when(event.getOptions()).thenReturn(List.of());

        // Mock the CommandManager's commands
        ICommand musicCommand = mock(IMusicCommand.class);
        ICommand miscCommand = Mockito.mock(IMiscCommand.class);

        when(commandManager.getMusicCommands()).thenReturn(List.of(musicCommand));
        when(commandManager.getMiscCommands()).thenReturn(List.of(miscCommand));

        // Test handle method
        helpCommand.handle(new CommandContext(event), mock(UserDto.class), 0);

        // Verify interactions
        //if we pass no args, a big list of commands are printed out in a formatted string
        verify(interactionHook, times(1)).sendMessageFormat(anyString());
    }

    @Test
    public void testHandleCommandWithCommandArg() {
        // Mock user interaction with a command argument

        // Mock the event's options to include a command argument
        OptionMapping optionMapping = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(List.of(optionMapping)); // Add an option to simulate a command argument

        // Mock the CommandManager's command
        ICommand musicCommand = mock(PlayCommand.class);
        when(commandManager.getCommand(anyString())).thenReturn(musicCommand);
        when(event.getOption("command")).thenReturn(optionMapping);
        when(optionMapping.getAsString()).thenReturn("play");
        when(musicCommand.getDescription()).thenReturn("");
        // Test handle method
        helpCommand.handle(new CommandContext(event), mock(UserDto.class), 0);

        // Verify interactions
        verify(interactionHook, times(1)).sendMessage(anyString());
    }

}