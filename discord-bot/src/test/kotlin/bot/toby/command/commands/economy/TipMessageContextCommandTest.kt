package bot.toby.command.commands.economy

import bot.toby.modal.modals.TipMessageModal
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Right-click a message → **Apps → Tip this**.
 *
 * The recipient comes off the message rather than out of an option, and the
 * message's coordinates ride into the form so the announcement can point back
 * at what the credit was for.
 */
class TipMessageContextCommandTest {

    private val callerId = 1L
    private val authorId = 99L
    private val channelId = 555L
    private val messageId = 777L

    private lateinit var command: TipMessageContextCommand
    private lateinit var event: MessageContextInteractionEvent
    private lateinit var author: User
    private lateinit var target: Message

    private val modals = mutableListOf<Modal>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = 7L)

    @BeforeEach
    fun setUp() {
        command = TipMessageContextCommand()

        author = mockk(relaxed = true) {
            every { idLong } returns authorId
            every { isBot } returns false
            every { effectiveName } returns "Them"
        }
        // Built outside the Message block: `Message` has its own `channelId`
        // (a String), which shadows the test's field inside that scope.
        val messageChannel = mockk<MessageChannelUnion>(relaxed = true)
        every { messageChannel.idLong } returns channelId

        target = mockk(relaxed = true) {
            every { idLong } returns messageId
            every { this@mockk.author } returns this@TipMessageContextCommandTest.author
            every { channel } returns messageChannel
            every { member } returns mockk<Member>(relaxed = true) { every { effectiveName } returns "Their Nick" }
        }
        event = mockk(relaxed = true) {
            every { guild } returns mockk<Guild>(relaxed = true) { every { idLong } returns 7L }
            every { this@mockk.target } returns this@TipMessageContextCommandTest.target
        }

        modals.clear()
        replies.clear()
        every { event.replyModal(capture(modals)) } returns mockk<ModalCallbackAction>(relaxed = true)
        every { event.reply(capture(replies)) } returns mockk<ReplyCallbackAction>(relaxed = true) {
            every { setEphemeral(any()) } returns this
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `it opens the tip form for whoever wrote the message`() {
        // The half `/tip` made you retype: the moment already knows who.
        command.handle(event, caller, 5)

        assertTrue(modals.single().id.startsWith("${TipMessageModal.MODAL_NAME}:$authorId:"), modals.single().id)
    }

    @Test
    fun `the form carries the message so the announcement can link back to it`() {
        // Without this the channel sees that credit moved but not what for,
        // which is the only thing tipping from a message adds over `/tip`.
        command.handle(event, caller, 5)

        assertEquals(
            TipMessageModal.askAmountId(authorId, channelId, messageId),
            modals.single().id,
        )
    }

    @Test
    fun `the form asks for an amount, since a right-click can't carry one`() {
        command.handle(event, caller, 5)

        val fields = modals.single().componentTree.findAll(TextInput::class.java).map { it.customId }
        assertEquals(listOf(TipMessageModal.FIELD_AMOUNT, TipMessageModal.FIELD_NOTE), fields)
    }

    @Test
    fun `the form names the recipient, since a right-click is easy to misland`() {
        command.handle(event, caller, 5)

        assertTrue(modals.single().title.contains("Their Nick"), modals.single().title)
    }

    @Test
    fun `it falls back to the account name for a member who has since left`() {
        every { target.member } returns null

        command.handle(event, caller, 5)

        assertTrue(modals.single().title.contains("Them"), modals.single().title)
    }

    @Test
    fun `tipping a bot is refused before the form opens`() {
        // TipService would bounce it, but a form that takes an amount and then
        // rejects it is a worse answer than a sentence.
        every { author.isBot } returns true

        command.handle(event, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("bot"), replies.single())
    }

    @Test
    fun `tipping your own message is refused before the form opens`() {
        every { author.idLong } returns callerId

        command.handle(event, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("yourself"), replies.single())
    }

    @Test
    fun `it does not defer, because a modal has to answer the interaction first`() {
        assertTrue(!command.defersReply)
    }

    @Test
    fun `it registers as a message context entry`() {
        assertEquals("Tip this", command.name)
        assertEquals(Command.Type.MESSAGE, command.commandData.type)
    }

    @Test
    fun `the entry name fits Discord's cap`() {
        assertTrue(TipMessageContextCommand.COMMAND_NAME.length <= 32)
    }
}
