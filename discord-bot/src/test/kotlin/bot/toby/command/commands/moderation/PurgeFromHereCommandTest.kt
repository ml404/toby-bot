package bot.toby.command.commands.moderation

import bot.toby.button.buttons.moderation.PurgeFromHereButton
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageHistory
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.function.Consumer

/**
 * Right-click a message → **Apps → Purge from here**.
 *
 * The entry itself deletes nothing: it counts what is in scope and offers a
 * button. These cover the counting and the refusals; the deleting is
 * [bot.toby.button.buttons.moderation.PurgeFromHereButton]'s.
 */
class PurgeFromHereCommandTest {

    private val anchorId = 4242L

    private lateinit var command: PurgeFromHereCommand
    private lateinit var event: MessageContextInteractionEvent
    private lateinit var channel: TextChannel
    private lateinit var member: Member
    private lateinit var selfMember: SelfMember
    private lateinit var anchor: Message

    private val replies = mutableListOf<String>()
    private val rows = mutableListOf<ActionRow>()
    private val caller = UserDto(discordId = 1L, guildId = 7L)

    @BeforeEach
    fun setUp() {
        command = PurgeFromHereCommand()

        anchor = messageAged(0).also { every { it.idLong } returns anchorId }
        every { anchor.author } returns mockk<User>(relaxed = true) { every { effectiveName } returns "Alice" }

        channel = mockk(relaxed = true) { every { type } returns ChannelType.TEXT }
        member = mockk(relaxed = true) { every { hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true }
        selfMember = mockk(relaxed = true) { every { hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true }

        // Stubbed outside the block: inside it, an unqualified `selfMember`
        // resolves to the Guild receiver's own getter, not the test's field.
        val guild = mockk<Guild>(relaxed = true)
        every { guild.selfMember } returns selfMember
        val union = mockk<MessageChannelUnion>(relaxed = true) {
            every { type } returns ChannelType.TEXT
            every { asTextChannel() } returns channel
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)

        event = mockk(relaxed = true) {
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns this@PurgeFromHereCommandTest.member
            every { this@mockk.channel } returns union
            every { target } returns anchor
            every { this@mockk.hook } returns hook
        }

        replies.clear()
        rows.clear()
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.addComponents(capture(rows)) } returns send
        every { send.setEphemeral(any()) } returns send

        historyAfter(emptyList())
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** Ages are relative to now, so the 14-day rule reads the same as in prod. */
    private fun messageAged(daysAgo: Long): Message = mockk(relaxed = true) {
        every { timeCreated } returns OffsetDateTime.now().minusDays(daysAgo)
    }

    /** Feeds the success callback of `getHistoryAfter(...).queue(ok, err)`. */
    private fun historyAfter(messages: List<Message>) {
        val history = mockk<MessageHistory>(relaxed = true) { every { retrievedHistory } returns messages }
        val action = mockk<MessageHistory.MessageRetrieveAction>(relaxed = true)
        every { channel.getHistoryAfter(anchorId, any<Int>()) } returns action
        val ok = slot<Consumer<MessageHistory>>()
        every { action.queue(capture(ok), any()) } answers { ok.captured.accept(history) }
    }

    private fun historyFails(reason: String) {
        val action = mockk<MessageHistory.MessageRetrieveAction>(relaxed = true)
        every { channel.getHistoryAfter(anchorId, any<Int>()) } returns action
        val err = slot<Consumer<Throwable>>()
        every { action.queue(any(), capture(err)) } answers { err.captured.accept(RuntimeException(reason)) }
    }

    private fun offeredButton(): Button? = rows.flatMap { it.components }.filterIsInstance<Button>().singleOrNull()

    @Test
    fun `it counts the message you pointed at plus everything after it`() {
        // The anchor is included: "from here" means this message onward, which
        // is the one you right-clicked because it started the mess.
        historyAfter(listOf(messageAged(0), messageAged(0)))

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("**3 messages**"), replies.single())
        assertEquals("Delete 3 messages", offeredButton()!!.label)
    }

    @Test
    fun `it deletes nothing by itself`() {
        historyAfter(listOf(messageAged(0)))

        command.handle(event, caller, 5)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        verify(exactly = 0) { anchor.delete() }
    }

    @Test
    fun `the confirmation names who wrote the message you pointed at`() {
        // The likeliest misfire is right-clicking the message next to the one
        // you meant, and the author is what tells you at a glance.
        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("Alice's message"), replies.single())
    }

    @Test
    fun `the confirmation says it can't be undone`() {
        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("can't be undone"), replies.single())
    }

    @Test
    fun `the button is styled as the destructive thing it is`() {
        command.handle(event, caller, 5)

        assertEquals(ButtonStyle.DANGER, offeredButton()!!.style)
        assertEquals("${PurgeFromHereButton.BUTTON_NAME}:$anchorId", offeredButton()!!.customId)
    }

    @Test
    fun `one message reads as one message`() {
        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("**1 message**"), replies.single())
        assertEquals("Delete 1 message", offeredButton()!!.label)
    }

    @Test
    fun `messages past the fourteen-day line are counted off rather than promised`() {
        historyAfter(listOf(messageAged(0), messageAged(30), messageAged(30)))

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("**2 messages**"), replies.single())
        assertTrue(replies.single().contains("2 older than 14 days skipped"), replies.single())
    }

    @Test
    fun `an anchor too old to delete is refused rather than offered`() {
        every { anchor.timeCreated } returns OffsetDateTime.now().minusDays(30)

        command.handle(event, caller, 5)

        assertTrue(rows.isEmpty())
        assertTrue(replies.single().contains("older than"), replies.single())
    }

    @Test
    fun `a full scan says so, since the mess may run past the cap`() {
        // Discord's bulk endpoint stops at 100, and silently doing 100 of 400
        // would read as "done".
        historyAfter((1..99).map { messageAged(0) })

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("**100 messages**"), replies.single())
        assertTrue(replies.single().contains("run it again"), replies.single())
    }

    @Test
    fun `a scan that didn't fill up says nothing about running again`() {
        historyAfter(listOf(messageAged(0)))

        command.handle(event, caller, 5)

        assertTrue(!replies.single().contains("run it again"), replies.single())
    }

    @Test
    fun `a caller without Manage Messages is turned away before anything is read`() {
        every { member.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns false

        command.handle(event, caller, 5)

        verify(exactly = 0) { channel.getHistoryAfter(any<Long>(), any<Int>()) }
        assertTrue(replies.single().contains("You need Manage Messages"), replies.single())
    }

    @Test
    fun `a bot without Manage Messages says so rather than failing later`() {
        every { selfMember.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns false

        command.handle(event, caller, 5)

        verify(exactly = 0) { channel.getHistoryAfter(any<Long>(), any<Int>()) }
        assertTrue(replies.single().contains("I need Manage Messages"), replies.single())
    }

    @Test
    fun `outside a text channel it declines, matching slash purge`() {
        every { event.channel } returns mockk<MessageChannelUnion>(relaxed = true) {
            every { type } returns ChannelType.VOICE
        }

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("text channels"), replies.single())
    }

    @Test
    fun `a history read that fails is reported rather than swallowed`() {
        historyFails("gateway said no")

        command.handle(event, caller, 5)

        assertTrue(replies.single().contains("gateway said no"), replies.single())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `it is hidden by default from members without Manage Messages`() {
        // A moderation action shouldn't sit in everybody's right-click menu.
        // Servers can still override this in Integrations settings, which is
        // why the handler checks the permission as well as declaring it.
        assertEquals(
            Permission.MESSAGE_MANAGE.rawValue,
            command.commandData.defaultPermissions.permissionsRaw,
        )
    }

    @Test
    fun `it registers as a message context entry`() {
        assertEquals("Purge from here", command.name)
        assertEquals(Command.Type.MESSAGE, command.commandData.type)
    }

    @Test
    fun `the entry name fits Discord's cap`() {
        assertTrue(PurgeFromHereCommand.COMMAND_NAME.length <= 32)
    }
}
