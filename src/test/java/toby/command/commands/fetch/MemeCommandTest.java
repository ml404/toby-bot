package toby.command.commands.fetch;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.command.CommandContext;
import toby.command.CommandTest;
import toby.dto.web.RedditAPIDto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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
        doReturn(webhookMessageCreateAction)
                .when(interactionHook)
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
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
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
        verify(interactionHook, times(1)).sendMessageEmbeds(any(), any(MessageEmbed[].class));
    }

    @Test
    public void testGetOptionData() {

        List<OptionData> optionDataList = memeCommand.getOptionData();

        // Check the size of the returned list
        assertEquals(3, optionDataList.size());

        // Check the properties of the OptionData objects
        OptionData subreddit = optionDataList.get(0);
        OptionData timePeriod = optionDataList.get(1);
        OptionData limit = optionDataList.get(2);

        assertEquals(OptionType.STRING, subreddit.getType());
        assertEquals("Which subreddit to pull the meme from", subreddit.getDescription());
        assertTrue(subreddit.isRequired());

        assertEquals(OptionType.STRING, timePeriod.getType());
        assertEquals("What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.", timePeriod.getDescription());
        assertFalse(timePeriod.isRequired());

        assertEquals(OptionType.INTEGER, limit.getType());
        assertEquals("Pick from top X posts of that day. Default 5.", limit.getDescription());
        assertFalse(limit.isRequired());

        // Check the choices of the 'timePeriod' option
        // Assuming that RedditAPIDto.TimePeriod.values() returns [DAY, WEEK, MONTH, ALL]
        List<Command.Choice> timePeriodChoices = timePeriod.getChoices();
        assertEquals(4, timePeriodChoices.size());
        assertEquals("day", timePeriodChoices.get(0).getName());
        assertEquals("day", timePeriodChoices.get(0).getAsString());
        assertEquals("week", timePeriodChoices.get(1).getName());
        assertEquals("week", timePeriodChoices.get(1).getAsString());
        assertEquals("month", timePeriodChoices.get(2).getName());
        assertEquals("month", timePeriodChoices.get(2).getAsString());
        assertEquals("all", timePeriodChoices.get(3).getName());
        assertEquals("all", timePeriodChoices.get(3).getAsString());
    }
}