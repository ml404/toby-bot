package toby.command.commands.misc;

import toby.command.CommandTest;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.ICommand;
import toby.jpa.dto.UserDto;

import java.util.List;

import static org.mockito.Mockito.*;

public class RandomCommandTest implements CommandTest {

    private RandomCommand randomCommand;

    @BeforeEach
    public void setUp() {
        setUpCommonMocks();
        randomCommand = new RandomCommand();
    }

    @AfterEach
    public void tearDown(){
        tearDownCommonMocks();
    }

    @Test
    public void testHandleCommandWithList() {
        // Mock the list of options provided by the user
        OptionMapping listOption = mock(OptionMapping.class);
        when(listOption.getAsString()).thenReturn("Option1,Option2,Option3");

        // Mock the event's options to return the list option
        when(event.getOption("list")).thenReturn(listOption);

        // Mock ICommand's deleteOriginal and queueAfter
        ICommand.deleteAfter(interactionHook, 0);

        // Call the handle method with the event
        randomCommand.handle(new CommandContext(event), mock(UserDto.class), 0);

        // Verify that the interactionHook's sendMessage method is called with a random option
        verify(interactionHook, times(1)).sendMessage(anyString()); // Note: This is just an example; the actual option may vary
    }

    @Test
    public void testHandleCommandWithoutList() {
        // Mock the event's options to be empty
        when(event.getOptions()).thenReturn(List.of());

        // Mock ICommand's deleteOriginal and queueAfter
        ICommand.deleteAfter(interactionHook, 0);

        // Call the handle method with the event
        randomCommand.handle(new CommandContext(event), mock(UserDto.class), 0);

        // Verify that the interactionHook's sendMessage method is called with the command's description
        verify(interactionHook, times(1)).sendMessage("Return one item from a list you provide with options separated by commas.");
    }
}
