package toby.command.commands.fetch;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.commands.CommandTest;
import toby.dto.web.RedditAPIDto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.mockito.Mockito.*;

class MemeCommandTest implements CommandTest {

    MemeCommand memeCommand;

    String jsonResponse = "{\"data\": {" +
            "\"children\": [" +
            "{ " +
            "\"data\": {" +
            "\"title\": \"Sample Reddit Post\"," +
            "\"url\": \"https://www.old.reddit.com/r/raimimemes/sample-post\"," +
            "\"author\": \"sample_author\"," +
            "\"over_18\": false," +
            "\"is_video\": false" +
            "}" +
            "}" +
            "]" +
            "}" +
            "}";

    @BeforeEach
    void setUp() {
        setUpCommonMocks();
        doReturn(messageCreateAction)
                .when(messageChannelUnion)
                .sendMessageEmbeds(any(), any(MessageEmbed[].class));
        memeCommand = new MemeCommand();
    }

    @AfterEach
    void tearDown() {
        tearDownCommonMocks();
        reset(messageChannelUnion);

    }

    @Test
    void test_memeCommandWithSubreddit_createsAndSendsEmbed() throws IOException {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping subredditOptionMapping = mock(OptionMapping.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity httpEntity = mock(HttpEntity.class);
        InputStream contentStream = new ByteArrayInputStream(jsonResponse.getBytes());
        when(subredditOptionMapping.getAsString()).thenReturn("raimimemes");
        when(event.getOption("subreddit")).thenReturn(subredditOptionMapping);
        when(httpClient.execute(any())).thenReturn(httpResponse);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpEntity.getContent()).thenReturn(contentStream);

        //Act
        memeCommand.handle(commandContext, httpClient, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(messageChannelUnion, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
    }

    @Test
    void test_memeCommandWithSubredditAndTimePeriod_createsAndSendsEmbed() throws IOException {
        //Arrange
        CommandContext commandContext = new CommandContext(event);
        OptionMapping subredditOptionMapping = mock(OptionMapping.class);
        OptionMapping timePeriodOptionMapping = mock(OptionMapping.class);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity httpEntity = mock(HttpEntity.class);
        InputStream contentStream = new ByteArrayInputStream(jsonResponse.getBytes());
        when(subredditOptionMapping.getAsString()).thenReturn("raimimemes");
        when(timePeriodOptionMapping.getAsString()).thenReturn(RedditAPIDto.TimePeriod.DAY.name().toUpperCase());
        when(event.getOption("subreddit")).thenReturn(subredditOptionMapping);
        when(event.getOption("timeperiod")).thenReturn(timePeriodOptionMapping);
        when(httpClient.execute(any())).thenReturn(httpResponse);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpEntity.getContent()).thenReturn(contentStream);

        //Act
        memeCommand.handle(commandContext, httpClient, requestingUserDto, 0);

        //Assert
        verify(interactionHook, times(1)).deleteOriginal();
        verify(messageChannelUnion, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
    }
}