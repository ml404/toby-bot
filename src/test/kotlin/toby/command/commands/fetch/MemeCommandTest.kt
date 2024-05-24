package toby.command.commands.fetch

import io.mockk.*
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
import toby.command.CommandContext
import toby.command.CommandTest
import toby.dto.web.RedditAPIDto
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

internal class MemeCommandTest : CommandTest {
    lateinit var memeCommand: MemeCommand

    private var jsonResponse: String = """{
        "data": {
            "children": [
                { 
                    "data": {
                        "title": "Sample Reddit Post",
                        "url": "https://www.old.reddit.com/r/raimimemes/sample-post",
                        "author": "sample_author",
                        "over_18": false,
                        "is_video": false
                    }
                }
            ]
        }
    }"""

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        every { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) } returns CommandTest.webhookMessageCreateAction
        memeCommand = MemeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(CommandTest.messageChannelUnion)
    }

    @Test
    @Throws(IOException::class)
    fun test_memeCommandWithSubreddit_createsAndSendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val subredditOptionMapping = mockk<OptionMapping>()
        val httpClient = mockk<HttpClient>()
        val httpResponse = mockk<HttpResponse>()
        val statusLine = mockk<StatusLine>()
        val httpEntity = mockk<HttpEntity>()
        val contentStream: InputStream = ByteArrayInputStream(jsonResponse.toByteArray())

        every { subredditOptionMapping.asString } returns "raimimemes"
        every { CommandTest.event.getOption("subreddit") } returns subredditOptionMapping
        every { httpClient.execute(any()) } returns httpResponse
        every { httpResponse.statusLine } returns statusLine
        every { statusLine.statusCode } returns 200
        every { httpResponse.entity } returns httpEntity
        every { httpEntity.content } returns contentStream

        //Act
        memeCommand.handle(commandContext, httpClient, CommandTest.requestingUserDto, 0)

        //Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    @Throws(IOException::class)
    fun test_memeCommandWithSubredditAndTimePeriod_createsAndSendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val subredditOptionMapping = mockk<OptionMapping>()
        val timePeriodOptionMapping = mockk<OptionMapping>()
        val httpClient = mockk<HttpClient>()
        val httpResponse = mockk<HttpResponse>()
        val statusLine = mockk<StatusLine>()
        val httpEntity = mockk<HttpEntity>()
        val contentStream: InputStream = ByteArrayInputStream(jsonResponse.toByteArray())

        every { subredditOptionMapping.asString } returns "raimimemes"
        every { timePeriodOptionMapping.asString } returns RedditAPIDto.TimePeriod.DAY.name.uppercase(Locale.getDefault())
        every { CommandTest.event.getOption("subreddit") } returns subredditOptionMapping
        every { CommandTest.event.getOption("timeperiod") } returns timePeriodOptionMapping
        every { httpClient.execute(any()) } returns httpResponse
        every { httpResponse.statusLine } returns statusLine
        every { statusLine.statusCode } returns 200
        every { httpResponse.entity } returns httpEntity
        every { httpEntity.content } returns contentStream

        //Act
        memeCommand.handle(commandContext, httpClient, CommandTest.requestingUserDto, 0)

        //Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageEmbeds(any(), *anyVararg()) }
    }

    @Test
    fun testGetOptionData() {
        val optionDataList = memeCommand.optionData

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
