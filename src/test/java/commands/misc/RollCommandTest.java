package commands.misc;

import commands.CommandTest;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.commands.misc.RollCommand;

import static org.mockito.Mockito.*;

public class RollCommandTest implements CommandTest {

    private RollCommand rollCommand;


    @BeforeEach
    public void setUp() {
        setUpCommonMocks();
        // Customize the behavior of sendMessageEmbeds
        doReturn(messageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));


        rollCommand = new RollCommand();
    }

    @Test
    public void testHandleDiceRoll() {
        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(event, 6, 1, 0);

        // Perform verifications as needed
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(messageCreateAction, times(1)).addActionRow(any(), any(), any(), any(), any());        // Add further verifications as needed
    }

}
