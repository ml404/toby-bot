package bot.toby.command.commands.fetch

import bot.toby.command.DefaultCommandContext
import bot.toby.dto.web.RedditAPIDto
import database.dto.user.UserDto
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

internal class MemeCommandTest {

    private lateinit var fetcher: RedditMemeFetcher
    private lateinit var memeCommand: MemeCommand

    private val event: SlashCommandInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val user: User = mockk(relaxed = true)
    private val member: Member = mockk(relaxed = true)
    private val replyAction: ReplyCallbackAction = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private val editAction: WebhookMessageEditAction<Message> = mockk(relaxed = true)

    private val userDto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        fetcher = mockk()
        // Unconfined so the launched IO coroutine resolves synchronously in tests.
        memeCommand = MemeCommand(fetcher, Dispatchers.Unconfined)

        every { event.hook } returns hook
        every { event.user } returns user
        every { event.member } returns member
        every { event.guild } returns mockk(relaxed = true)
        every { user.effectiveName } returns "Asker"
        every { member.effectiveName } returns "Member"
        every { event.deferReply() } returns replyAction
        every { replyAction.queue() } just Runs

        every { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) } returns editAction
        every { editAction.setComponents(any<ActionRow>()) } returns editAction
        every { editAction.queue() } just Runs
        every { editAction.queue(any()) } just Runs

        every { userDto.memePermission } returns true

        every { event.getOption("timeperiod") } returns mockk {
            every { asString } returns RedditAPIDto.TimePeriod.DAY.name.uppercase(Locale.getDefault())
        }
        every { event.getOption("limit") } returns mockk {
            every { asInt } returns 1
        }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `successful fetch edits the original with a loading embed then a result embed plus a Re-roll button`() {
        val subredditOptionMapping = mockk<OptionMapping> { every { asString } returns "raimimemes" }
        every { event.getOption("subreddit") } returns subredditOptionMapping
        coEvery { fetcher.fetch("raimimemes", "day", 1) } returns
            RedditMemeFetcher.Result.Success("Sample Reddit Post", "https://reddit/sample", "sample_author")

        memeCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        // Loading edit (immediate) + result edit (after fetch) = 2 calls.
        verify(exactly = 2) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 1) { editAction.setComponents(any<ActionRow>()) }
    }

    @Test
    fun `a fetch error edits the original with just an error embed and no Re-roll button`() {
        val subredditOptionMapping = mockk<OptionMapping> { every { asString } returns "raimimemes" }
        every { event.getOption("subreddit") } returns subredditOptionMapping
        coEvery { fetcher.fetch("raimimemes", "day", 1) } returns
            RedditMemeFetcher.Result.Error("No memes found in r/raimimemes.")

        memeCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        // Loading edit + error edit = 2 calls, but no re-roll row.
        verify(exactly = 2) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 0) { editAction.setComponents(any<ActionRow>()) }
    }

    @Test
    fun `users without meme permission get the default permission-error followup and no fetch happens`() {
        every { userDto.memePermission } returns false

        memeCommand.handle(DefaultCommandContext(event), userDto, deleteDelay = 0)

        verify(exactly = 0) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        coVerify(exactly = 0) { fetcher.fetch(any(), any(), any()) }
    }

    @Test
    fun testGetOptionData() {
        val optionDataList = memeCommand.optionData

        Assertions.assertEquals(3, optionDataList.size)
        val subreddit = optionDataList[0]
        val timePeriod = optionDataList[1]
        val limit = optionDataList[2]

        Assertions.assertEquals(OptionType.STRING, subreddit.type)
        Assertions.assertEquals("Which subreddit to pull the meme from", subreddit.description)
        Assertions.assertTrue(subreddit.isRequired)

        Assertions.assertEquals(OptionType.STRING, timePeriod.type)
        Assertions.assertEquals(
            "What time period filter to apply to the subreddit (e.g. day/week/month/all). Default day.",
            timePeriod.description,
        )
        Assertions.assertFalse(timePeriod.isRequired)

        Assertions.assertEquals(OptionType.INTEGER, limit.type)
        Assertions.assertEquals("Pick from top X posts of that day. Default 5.", limit.description)
        Assertions.assertFalse(limit.isRequired)

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

    @Test
    fun `rerollRow encodes args round-trip via decodeReroll`() {
        val row = MemeEmbeds.rerollRow("memes", "week", 25)
        requireNotNull(row) { "encoded id should fit within Discord's 100-char limit" }
        val button = row.components.first() as net.dv8tion.jda.api.components.buttons.Button
        val args = MemeEmbeds.decodeReroll(button.customId!!)
        Assertions.assertEquals(MemeEmbeds.RerollArgs("memes", "week", 25), args)
    }
}
