package bot.toby.button.buttons.moderation

import core.button.ButtonContext
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageHistory
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import bot.toby.moderation.MessagePurge
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.function.Consumer

/**
 * The half of **Purge from here** that actually deletes.
 *
 * Everything here happens a moment after somebody read a count, so what these
 * cover is mostly the ways the channel can have moved on since.
 */
class PurgeFromHereButtonTest {

    private val anchorId = 4242L

    private lateinit var button: PurgeFromHereButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var channel: TextChannel
    private lateinit var member: Member
    private lateinit var selfMember: SelfMember
    private lateinit var anchor: Message
    private lateinit var edit: WebhookMessageEditAction<Message>

    private val edits = mutableListOf<String>()
    private val deleted = mutableListOf<Collection<Message>>()
    private val caller = UserDto(discordId = 1L, guildId = 7L)

    @BeforeEach
    fun setUp() {
        button = PurgeFromHereButton()

        anchor = messageAged(0)
        channel = mockk(relaxed = true) { every { type } returns ChannelType.TEXT }
        member = mockk(relaxed = true) { every { hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true }
        selfMember = mockk(relaxed = true) { every { hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true }

        val guild = mockk<Guild>(relaxed = true)
        every { guild.selfMember } returns selfMember

        val union = mockk<MessageChannelUnion>(relaxed = true) {
            every { type } returns ChannelType.TEXT
            every { asTextChannel() } returns channel
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "${PurgeFromHereButton.BUTTON_NAME}:$anchorId"
            every { this@mockk.channel } returns union
            every { this@mockk.hook } returns hook
            every { user } returns mockk<User>(relaxed = true) { every { name } returns "Mod" }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@PurgeFromHereButtonTest.event
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns this@PurgeFromHereButtonTest.member
        }

        edits.clear()
        deleted.clear()
        edit = mockk(relaxed = true)
        every { hook.editOriginal(capture(edits)) } returns edit
        every { edit.setComponents() } returns edit

        anchorResolves(anchor)
        historyAfter(emptyList())
        deleteSucceeds()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun messageAged(daysAgo: Long): Message = mockk(relaxed = true) {
        every { timeCreated } returns OffsetDateTime.now().minusDays(daysAgo)
    }

    private fun anchorResolves(message: Message) {
        val action = mockk<RestAction<Message>>(relaxed = true)
        every { channel.retrieveMessageById(anchorId) } returns action
        val ok = slot<Consumer<Message>>()
        every { action.queue(capture(ok), any()) } answers { ok.captured.accept(message) }
    }

    private fun anchorIsGone() {
        val action = mockk<RestAction<Message>>(relaxed = true)
        every { channel.retrieveMessageById(anchorId) } returns action
        val err = slot<Consumer<Throwable>>()
        every { action.queue(any(), capture(err)) } answers { err.captured.accept(RuntimeException("404")) }
    }

    private fun historyAfter(messages: List<Message>) {
        val history = mockk<MessageHistory>(relaxed = true) { every { retrievedHistory } returns messages }
        val action = mockk<MessageHistory.MessageRetrieveAction>(relaxed = true)
        every { channel.getHistoryAfter(anchorId, any<Int>()) } returns action
        val ok = slot<Consumer<MessageHistory>>()
        every { action.queue(capture(ok), any()) } answers { ok.captured.accept(history) }
    }

    /**
     * Both delete paths, because which one runs depends on the batch size —
     * Discord's bulk endpoint refuses a single message, so [MessagePurge] sends
     * that one through `Message.delete()` instead.
     */
    private fun deleteSucceeds() = stubDeletes { ok, _ -> ok.accept(null) }

    private fun deleteFails(reason: String) =
        stubDeletes { _, err -> err.accept(RuntimeException(reason)) }

    private fun stubDeletes(fire: (Consumer<Void?>, Consumer<Throwable>) -> Unit) {
        val bulk = mockk<RestAction<Void>>(relaxed = true)
        every { channel.deleteMessages(capture(deleted)) } returns bulk
        val bulkOk = slot<Consumer<Void?>>()
        val bulkErr = slot<Consumer<Throwable>>()
        every { bulk.queue(capture(bulkOk), capture(bulkErr)) } answers { fire(bulkOk.captured, bulkErr.captured) }

        val single = mockk<AuditableRestAction<Void>>(relaxed = true)
        every { anchor.delete() } answers { deleted += listOf(anchor); single }
        every { single.reason(any()) } returns single
        val singleOk = slot<Consumer<Void?>>()
        val singleErr = slot<Consumer<Throwable>>()
        every { single.queue(capture(singleOk), capture(singleErr)) } answers {
            fire(singleOk.captured, singleErr.captured)
        }
    }

    @Test
    fun `it deletes the anchor along with everything after it`() {
        val after = listOf(messageAged(0), messageAged(0))
        historyAfter(after)

        button.handle(ctx, caller, 5)

        assertEquals(listOf(anchor) + after, deleted.single().toList())
        assertTrue(edits.single().contains("Deleted 3 messages"), edits.single())
    }

    @Test
    fun `it re-reads the channel rather than trusting the count on the button`() {
        // Messages arrive while somebody reads a confirmation, and a purge that
        // stopped at the old count would leave behind exactly the ones that
        // prompted it.
        historyAfter((1..4).map { messageAged(0) })

        button.handle(ctx, caller, 5)

        assertEquals(5, deleted.single().size)
        assertTrue(edits.single().contains("Deleted 5 messages"), edits.single())
    }

    @Test
    fun `the result replaces the confirmation and takes the button with it`() {
        // A second press would otherwise sweep up whatever had been posted
        // since the first.
        button.handle(ctx, caller, 5)

        assertEquals(1, edits.size)
        verify(exactly = 1) { edit.setComponents() }
    }

    @Test
    fun `messages past the fourteen-day line are left alone and accounted for`() {
        historyAfter(listOf(messageAged(0), messageAged(40)))

        button.handle(ctx, caller, 5)

        assertEquals(2, deleted.single().size)
        assertTrue(edits.single().contains("1 older than 14 days skipped"), edits.single())
    }

    @Test
    fun `an anchor that aged out between offer and press deletes nothing`() {
        every { anchor.timeCreated } returns OffsetDateTime.now().minusDays(30)

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        assertTrue(edits.single().contains("older than 14 days"), edits.single())
    }

    @Test
    fun `an anchor deleted between offer and press is reported`() {
        // Somebody else got there first, and "everything after it" is a
        // different question now.
        anchorIsGone()

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        assertTrue(edits.single().contains("has gone"), edits.single())
    }

    @Test
    fun `a failed delete says why`() {
        deleteFails("Missing Permissions")

        button.handle(ctx, caller, 5)

        assertTrue(edits.single().contains("Missing Permissions"), edits.single())
    }

    @Test
    fun `permission is re-checked, since a role can change between offer and press`() {
        every { member.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns false

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { channel.retrieveMessageById(any<Long>()) }
        assertTrue(edits.single().contains("You need Manage Messages"), edits.single())
    }

    @Test
    fun `the bot losing the permission is reported rather than failing mid-delete`() {
        every { selfMember.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns false

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { channel.retrieveMessageById(any<Long>()) }
        assertTrue(edits.single().contains("I need Manage Messages"), edits.single())
    }

    @Test
    fun `an id that arrives mangled deletes nothing`() {
        every { event.componentId } returns "${PurgeFromHereButton.BUTTON_NAME}:nonsense"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { channel.retrieveMessageById(any<Long>()) }
        assertTrue(edits.single().contains("lost track"), edits.single())
    }

    @Test
    fun `it edits rather than replies, so nothing is deferred twice`() {
        assertTrue(button.defersEdit)
    }

    @Test
    fun `the button carries the anchor and counts in its label`() {
        assertEquals("${PurgeFromHereButton.BUTTON_NAME}:$anchorId", PurgeFromHereButton.button(anchorId, 3).customId)
        assertEquals("Delete 3 messages", PurgeFromHereButton.button(anchorId, 3).label)
        assertEquals("Delete 1 message", PurgeFromHereButton.button(anchorId, 1).label)
    }
}
