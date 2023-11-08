package toby.command.commands.misc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IUserService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static toby.command.commands.misc.EightBallCommand.*;

class EightBallCommandTest implements CommandTest {


    private EightBallCommand command;
    @Mock
    IUserService userService;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        userService = mock(IUserService.class);
        command = new EightBallCommand(userService);
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
    }

    @Test
    public void testCommand_WithNotTom(){
        // Create a CommandContext
        CommandContext ctx = new CommandContext(event);

        // Mock requestingUserDto
        UserDto requestingUserDto = new UserDto(1L, 1L, true, true, true, true, 0L, null); // You can set the user as needed
        Integer deleteDelay = 0; // Set your desired deleteDelay

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay);

        // Verify that the message was sent with the expected content
        // You can use Mockito.verify() to check if event.getHook().sendMessage(...) was called with the expected message content.
        // For example:
        verify(event.getHook()).sendMessageFormat(eq("MAGIC 8-BALL SAYS: %s."), anyString());

    }

    @Test
    public void testCommand_WithTom(){
        // Create a CommandContext
        CommandContext ctx = new CommandContext(event);

        // Mock requestingUserDto
        UserDto requestingUserDto = new UserDto(TOMS_DISCORD_ID, 1L, true, true, true, true, 0L, null); // You can set the user as needed
        Integer deleteDelay = 0; // Set your desired deleteDelay

        when(userService.updateUser(any(UserDto.class))).thenReturn(requestingUserDto);

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay);

        // Verify that the message was sent with the expected content
        verify(event.getHook()).sendMessageFormat(eq("MAGIC 8-BALL SAYS: Don't fucking talk to me."));
        verify(event.getHook()).sendMessageFormat(eq("Deducted: %d social credit."), anyInt());
        verify(userService, times(1)).updateUser(any(UserDto.class));

        // You can also verify that ICommand.deleteAfter was called with the expected arguments.
    }
}