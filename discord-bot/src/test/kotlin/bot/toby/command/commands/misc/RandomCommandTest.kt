package bot.toby.command.commands.misc

import bot.toby.command.DefaultCommandContext
import database.dto.user.UserDto
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RandomCommandTest {

    private lateinit var command: RandomCommand

    private val event: SlashCommandInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val user: User = mockk(relaxed = true)
    private val replyAction: ReplyCallbackAction = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private val editAction: WebhookMessageEditAction<Message> = mockk(relaxed = true)

    private val dto: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        command = RandomCommand()

        every { event.hook } returns hook
        every { event.user } returns user
        every { user.effectiveName } returns "Asker"
        every { event.deferReply() } returns replyAction
        every { replyAction.queue() } just Runs

        every { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) } returns editAction
        every { editAction.setComponents(any<ActionRow>()) } returns editAction
        every { editAction.queue() } just Runs
        every { editAction.queue(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `picks one option, edits with an embed and stamps a Pick-another button`() {
        val listOption = mockk<OptionMapping> {
            every { asString } returns "Option1,Option2,Option3"
        }
        every { event.getOption(RandomCommand.LIST) } returns listOption

        command.handle(DefaultCommandContext(event), dto, deleteDelay = 0)

        verify(exactly = 1) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 1) { editAction.setComponents(any<ActionRow>()) }
    }

    @Test
    fun `empty input renders the no-options embed and skips the button row`() {
        every { event.getOption(RandomCommand.LIST) } returns null

        command.handle(DefaultCommandContext(event), dto, deleteDelay = 0)

        verify(exactly = 1) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 0) { editAction.setComponents(any<ActionRow>()) }
    }

    @Test
    fun `parseOptions trims whitespace and filters empties`() {
        assertEquals(
            listOf("Option1", "Option2", "Option3"),
            RandomCommand.parseOptions(" Option1 , Option2 , , Option3 "),
        )
    }

    @Test
    fun `pickAgainRow encodes options round-trip via decodeOptions`() {
        val original = listOf("pizza", "burger", "tacos")
        val row = RandomEmbeds.pickAgainRow(original)
        requireNotNull(row) { "encoded id should fit within Discord's 100-char limit" }
        val button = row.components.first() as net.dv8tion.jda.api.components.buttons.Button
        assertEquals(original, RandomEmbeds.decodeOptions(button.customId!!))
    }

    @Test
    fun `pickAgainRow returns null when the encoded id would exceed the 100-char limit`() {
        // 30 chars per option * 5 options ~= 150 chars after url encoding;
        // well past the 100-char ceiling, so the button must be omitted.
        val tooLong = List(5) { "this-is-a-fairly-long-option-$it" }
        assertEquals(null, RandomEmbeds.pickAgainRow(tooLong))
    }
}
