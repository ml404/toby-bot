package toby.command.commands.moderation;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.emote.Emotes;

import static org.mockito.Mockito.*;

class PollCommandTest implements CommandTest {

    PollCommand pollCommand;

    @Mock
    private Emoji tobyEmote;

    @BeforeEach
    public void setup() {
        setUpCommonMocks();
        doReturn(messageCreateAction)
                .when(messageChannelUnion)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));
        pollCommand = new PollCommand();
    }

    @AfterEach
    public void teardown() {
        tearDownCommonMocks();
        reset(messageChannelUnion);
    }

    @Test
    void test_pollCommandWithChoices_sendsEmbed() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping choicesOptionMapping = mock(OptionMapping.class);
        when(event.getOption("choices")).thenReturn(choicesOptionMapping);
        when(choicesOptionMapping.getAsString()).thenReturn("Choice1, Choice2");

        //Act
        pollCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(messageChannelUnion, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    void test_pollCommandWithChoicesAndQuestion_sendsEmbed() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping choicesOptionMapping = mock(OptionMapping.class);
        OptionMapping questionOptionMapping = mock(OptionMapping.class);
        when(event.getOption("choices")).thenReturn(choicesOptionMapping);
        when(event.getOption("question")).thenReturn(questionOptionMapping);
        when(choicesOptionMapping.getAsString()).thenReturn("Choice1, Choice2");
        when(questionOptionMapping.getAsString()).thenReturn("Question");

        //Act
        pollCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(messageChannelUnion, times(1)).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    void test_pollCommandWithoutChoices_sendsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);

        //Act
        pollCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(messageChannelUnion, times(0)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(interactionHook, times(1)).sendMessage(eq("Start a poll for every user in the server who has read permission in the channel you're posting to"));
    }

    @Test
    void test_pollCommandWithTooManyChoices_sendsError() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping choicesOptionMapping = mock(OptionMapping.class);
        when(event.getOption("choices")).thenReturn(choicesOptionMapping);
        when(choicesOptionMapping.getAsString()).thenReturn("Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1");
        when(jda.getEmojiById(Emotes.TOBY)).thenReturn((RichCustomEmoji) tobyEmote);
        //Act
        pollCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(messageChannelUnion, times(0)).sendMessageEmbeds(any(MessageEmbed.class));
        verify(interactionHook, times(1)).sendMessageFormat(eq("Please keep the poll size under 10 items, or else %s."), eq(tobyEmote));
    }
}