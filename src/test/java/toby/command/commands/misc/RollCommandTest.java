package toby.command.commands.misc;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.jpa.dto.UserDto;

import java.util.List;

import static org.mockito.Mockito.*;

public class RollCommandTest implements CommandTest {

    private RollCommand rollCommand;


    @BeforeEach
    public void setUp() {
        setUpCommonMocks();
        // Customize the behavior of sendMessageEmbeds
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));


        rollCommand = new RollCommand();
    }

    public void tearDown() {
        tearDownCommonMocks();
    }


    @Test
    public void testRollCommand() {
        // Arrange
        CommandContext ctx = new CommandContext(event);
        UserDto userDto = mock(UserDto.class);
        int deleteDelay = 0;
        Button reroll = Button.primary("resend_last_request", "Click to Reroll");
        Button rollD20 = Button.primary("roll" + ":" + "20, 1, 0", "Roll D20");
        Button rollD10 = Button.primary("roll" + ":" + "10, 1, 0", "Roll D10");
        Button rollD6 = Button.primary("roll" + ":" + "6, 1, 0", "Roll D6");
        Button rollD4 = Button.primary("roll" + ":" + "4, 1, 0", "Roll D4");


        OptionMapping number = mock(OptionMapping.class);
        OptionMapping amount = mock(OptionMapping.class);
        OptionMapping modifier = mock(OptionMapping.class);
        when(event.getOptions()).thenReturn(List.of(number, amount, modifier));
        when(event.getOption("number")).thenReturn(number);
        when(event.getOption("amount")).thenReturn(amount);
        when(event.getOption("modifier")).thenReturn(modifier);
        when(number.getAsInt()).thenReturn(6);
        when(amount.getAsInt()).thenReturn(1);
        when(modifier.getAsInt()).thenReturn(0);
        when(webhookMessageCreateAction.addActionRow(reroll, rollD20, rollD10, rollD6, rollD4)).thenReturn(webhookMessageCreateAction);

        // Act
        rollCommand.handle(ctx, userDto, deleteDelay);

        // Assert
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(1)).addActionRow(eq(reroll), eq(rollD20), eq(rollD10), eq(rollD6), eq(rollD4));
    }

    @Test
    public void testHandleDiceRoll() {
        // Call the handleDiceRoll method
        rollCommand.handleDiceRoll(event, 6, 1, 0);

        // Perform verifications as needed
        verify(event, times(1)).deferReply();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
        verify(webhookMessageCreateAction, times(1)).addActionRow(any(), any(), any(), any(), any());
    }
}
