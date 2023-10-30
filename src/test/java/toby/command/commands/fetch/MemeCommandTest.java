package toby.command.commands.fetch;

import com.github.natanbc.reliqua.request.PendingRequest;
import me.duncte123.botcommons.web.WebUtils;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;

import static org.mockito.Mockito.*;

class MemeCommandTest implements CommandTest {

    MemeCommand memeCommand;

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));
        memeCommand = new MemeCommand();
    }

    @AfterEach
    void tearDown() {
    tearDownCommonMocks();
    }

    @Test
    void handle() {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping subredditOptionMapping = mock(OptionMapping.class);
        WebUtils webUtils = mock(WebUtils.class);
        when(subredditOptionMapping.getAsString()).thenReturn("raimimemes");
        when(event.getOption("subreddit")).thenReturn(subredditOptionMapping);
        PendingRequest pendingRequest = mock(PendingRequest.class);
        when(webUtils.getJSONObject(anyString())).thenReturn(pendingRequest);

        //Act
        memeCommand.handle(commandContext, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
    }
}