package toby.command.commands.misc;

import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.jpa.dto.UserDto;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChCommandTest implements CommandTest {

    ChCommand command;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        command = new ChCommand();
        // Mock OptionMapping for the MESSAGE option
        OptionMapping messageOption = Mockito.mock(OptionMapping.class);
        when(messageOption.getAsString()).thenReturn("hello world");
        when(event.getOption("message")).thenReturn(messageOption);

        // Mock the event to return the MESSAGE option
        when(event.getOption(anyString())).thenReturn(messageOption);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    public void testHandle() {
        // Create a CommandContext
        CommandContext ctx = new CommandContext(event);

        // Mock requestingUserDto
        UserDto requestingUserDto = new UserDto(); // You can set the user as needed
        Integer deleteDelay = 0; // Set your desired deleteDelay

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay);

        // Verify that the message was sent with the expected content
        // You can use Mockito.verify() to check if event.getHook().sendMessage(...) was called with the expected message content.
        // For example:
        verify(event.getHook()).sendMessage("Oh! I think you mean: 'chello chorld'");
    }
}