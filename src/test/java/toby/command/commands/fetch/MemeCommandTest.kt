package toby.command.commands.fetch

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.StatusLine
import org.apache.http.client.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandContext
import toby.command.CommandTest
import toby.dto.web.RedditAPIDto
import java.io.*
import java.util.*

internal class MemeCommandTest : CommandTest {
    var memeCommand: MemeCommand? = null

    var jsonResponse: String = "{\"data\": {" +
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
            "}"

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        Mockito.doReturn(CommandTest.webhookMessageCreateAction)
            .`when`(CommandTest.interactionHook)
            .sendMessageEmbeds(
                ArgumentMatchers.any(), anyVararg()

            )
        memeCommand = MemeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(CommandTest.messageChannelUnion)
    }

    @Test
    @Throws(IOException::class)
    fun test_memeCommandWithSubreddit_createsAndSendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val subredditOptionMapping = Mockito.mock(OptionMapping::class.java)
        val httpClient = Mockito.mock(HttpClient::class.java)
        val httpResponse = Mockito.mock(HttpResponse::class.java)
        val statusLine = Mockito.mock(StatusLine::class.java)
        val httpEntity = Mockito.mock(HttpEntity::class.java)
        val contentStream: InputStream = ByteArrayInputStream(jsonResponse.toByteArray())
        Mockito.`when`(subredditOptionMapping.asString).thenReturn("raimimemes")
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("subreddit"))
            .thenReturn(subredditOptionMapping)
        Mockito.`when`(httpClient.execute(ArgumentMatchers.any())).thenReturn(httpResponse)
        Mockito.`when`(httpResponse.statusLine).thenReturn(statusLine)
        Mockito.`when`(statusLine.statusCode).thenReturn(200)
        Mockito.`when`(httpResponse.entity).thenReturn(httpEntity)
        Mockito.`when`(httpEntity.content).thenReturn(contentStream)

        //Act
        memeCommand!!.handle(commandContext, httpClient, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(ArgumentMatchers.any(), anyVararg())
    }

    @Test
    @Throws(IOException::class)
    fun test_memeCommandWithSubredditAndTimePeriod_createsAndSendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val subredditOptionMapping = Mockito.mock(OptionMapping::class.java)
        val timePeriodOptionMapping = Mockito.mock(OptionMapping::class.java)
        val httpClient = Mockito.mock(HttpClient::class.java)
        val httpResponse = Mockito.mock(HttpResponse::class.java)
        val statusLine = Mockito.mock(StatusLine::class.java)
        val httpEntity = Mockito.mock(HttpEntity::class.java)
        val contentStream: InputStream = ByteArrayInputStream(jsonResponse.toByteArray())
        Mockito.`when`(subredditOptionMapping.asString).thenReturn("raimimemes")
        Mockito.`when`(timePeriodOptionMapping.asString)
            .thenReturn(RedditAPIDto.TimePeriod.DAY.name.uppercase(Locale.getDefault()))
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("subreddit"))
            .thenReturn(subredditOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("timeperiod"))
            .thenReturn(timePeriodOptionMapping)
        Mockito.`when`(httpClient.execute(ArgumentMatchers.any())).thenReturn(httpResponse)
        Mockito.`when`(httpResponse.statusLine).thenReturn(statusLine)
        Mockito.`when`(statusLine.statusCode).thenReturn(200)
        Mockito.`when`(httpResponse.entity).thenReturn(httpEntity)
        Mockito.`when`(httpEntity.content).thenReturn(contentStream)

        //Act
        memeCommand!!.handle(commandContext, httpClient, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageEmbeds(ArgumentMatchers.any(), anyVararg())
    }

    @Test
    fun testGetOptionData() {
        val optionDataList = memeCommand!!.optionData

        // Check the size of the returned list
        Assertions.assertEquals(3, optionDataList.size)

        // Check the properties of the OptionData objects
        val subreddit = optionDataList[0]
        val timePeriod = optionDataList[1]
        val limit = optionDataList[2]

        Assertions.assertEquals(OptionType.STRING, subreddit.type)
        Assertions.assertEquals("Which subreddit to pull the meme from", subreddit.description)
        Assertions.assertTrue(subreddit.isRequired)

        Assertions.assertEquals(OptionType.STRING, timePeriod.type)
        Assertions.assertEquals(
            "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.",
            timePeriod.description
        )
        Assertions.assertFalse(timePeriod.isRequired)

        Assertions.assertEquals(OptionType.INTEGER, limit.type)
        Assertions.assertEquals("Pick from top X posts of that day. Default 5.", limit.description)
        Assertions.assertFalse(limit.isRequired)

        // Check the choices of the 'timePeriod' option
        // Assuming that RedditAPIDto.TimePeriod.values() returns [DAY, WEEK, MONTH, ALL]
        val timePeriodChoices = timePeriod.choices
        Assertions.assertEquals(4, timePeriodChoices.size)
        Assertions.assertEquals("day", timePeriodChoices[0].name)
        Assertions.assertEquals("day", timePeriodChoices[0].asString)
        Assertions.assertEquals("week", timePeriodChoices[1].name)
        Assertions.assertEquals("week", timePeriodChoices[1].asString)
        Assertions.assertEquals("month", timePeriodChoices[2].name)
        Assertions.assertEquals("month", timePeriodChoices[2].asString)
        Assertions.assertEquals("all", timePeriodChoices[3].name)
        Assertions.assertEquals("all", timePeriodChoices[3].asString)
    }
}