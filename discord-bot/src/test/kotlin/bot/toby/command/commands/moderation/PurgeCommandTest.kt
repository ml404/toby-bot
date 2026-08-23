package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.moderation.MessagePurge
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageHistory
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.function.Consumer

internal class PurgeCommandTest : CommandTest {
    private lateinit var purgeCommand: PurgeCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        purgeCommand = PurgeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_PurgeRejectsNonTextChannel() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(relaxed = true)
        every { channelUnion.type } returns ChannelType.VOICE
        every { event.channel } returns channelUnion

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_PurgeRejectsMissingMemberPermission() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns false
        every { botMember.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_PurgeRejectsCountOutOfRange() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true
        every { botMember.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true
        val countOpt = mockk<OptionMapping>()
        every { event.getOption("count") } returns countOpt
        every { countOpt.asLong } returns 500L
        every { event.getOption("user") } returns null

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
    // --- the half that actually deletes -------------------------------------
    //
    // Everything above stops before the channel is read. These drive the
    // retrievePast callback, which is where /purge decides what it is allowed
    // to remove — the 14-day rule, the user filter, and the one-versus-many
    // split that decides which Discord endpoint gets used.

    private val replies = mutableListOf<String>()
    private val deleted = mutableListOf<Collection<Message>>()

    /** How many messages each scan asked the channel for. */
    private val scanned = mutableListOf<Int>()

    /** A text channel the caller and the bot may both purge in. */
    private fun purgeableChannel(): TextChannel {
        val union = mockk<MessageChannelUnion>(relaxed = true, moreInterfaces = arrayOf(TextChannel::class))
        every { union.type } returns ChannelType.TEXT
        every { event.channel } returns union
        val channel = union as TextChannel
        every { member.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true
        every { botMember.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns true
        replies.clear()
        deleted.clear()
        scanned.clear()
        every { event.hook.sendMessage(capture(replies)) } returns webhookMessageCreateAction
        return channel
    }

    private fun message(daysAgo: Long = 0, authorId: Long = 1L): Message = mockk(relaxed = true) {
        every { timeCreated } returns OffsetDateTime.now().minusDays(daysAgo)
        every { author } returns mockk<User>(relaxed = true) { every { idLong } returns authorId }
    }

    private fun retrieveAction(channel: TextChannel): RestAction<List<Message>> {
        val history = mockk<MessageHistory>(relaxed = true)
        val action = mockk<RestAction<List<Message>>>(relaxed = true)
        every { channel.history } returns history
        every { history.retrievePast(capture(scanned)) } returns action
        return action
    }

    private fun historyOf(channel: TextChannel, messages: List<Message>) {
        val action = retrieveAction(channel)
        val ok = slot<Consumer<List<Message>>>()
        every { action.queue(capture(ok), any()) } answers { ok.captured.accept(messages) }
    }

    private fun historyFails(channel: TextChannel, reason: String) {
        val action = retrieveAction(channel)
        val err = slot<Consumer<Throwable>>()
        every { action.queue(any(), capture(err)) } answers { err.captured.accept(RuntimeException(reason)) }
    }

    /** Both endpoints: the bulk one refuses a single message, so one goes via delete(). */
    private fun deletesSucceed(channel: TextChannel, single: Message? = null) =
        stubDeletes(channel, single) { ok, _ -> ok.accept(null) }

    private fun deletesFail(channel: TextChannel, reason: String) =
        stubDeletes(channel, null) { _, err -> err.accept(RuntimeException(reason)) }

    private fun stubDeletes(
        channel: TextChannel,
        single: Message?,
        fire: (Consumer<Void?>, Consumer<Throwable>) -> Unit,
    ) {
        val bulk = mockk<RestAction<Void>>(relaxed = true)
        every { channel.deleteMessages(capture(deleted)) } returns bulk
        val bulkOk = slot<Consumer<Void?>>()
        val bulkErr = slot<Consumer<Throwable>>()
        every { bulk.queue(capture(bulkOk), capture(bulkErr)) } answers { fire(bulkOk.captured, bulkErr.captured) }

        // Named, because inside an `answers` block `it` is mockk's Call.
        single?.let { msg ->
            val one = mockk<AuditableRestAction<Void>>(relaxed = true)
            every { msg.delete() } answers { deleted.add(listOf(msg)); one }
            every { one.reason(any()) } returns one
            val ok = slot<Consumer<Void?>>()
            val err = slot<Consumer<Throwable>>()
            every { one.queue(capture(ok), capture(err)) } answers { fire(ok.captured, err.captured) }
        }
    }

    private fun countOption(count: Long?) {
        every { event.getOption("count") } returns count?.let { c ->
            mockk<OptionMapping>(relaxed = true) { every { asLong } returns c }
        }
    }

    private fun userOption(id: Long?) {
        every { event.getOption("user") } returns id?.let { uid ->
            mockk<OptionMapping>(relaxed = true) {
                every { asUser } returns mockk<User>(relaxed = true) { every { idLong } returns uid }
            }
        }
    }

    @Test
    fun `it deletes what the channel gave back and says how many`() {
        val channel = purgeableChannel()
        countOption(3); userOption(null)
        historyOf(channel, listOf(message(), message(), message()))
        deletesSucceed(channel)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertEquals(3, deleted.single().size)
        assertTrue(replies.single().contains("Deleted 3 messages"), replies.single())
    }

    @Test
    fun `a user filter deletes only that user's messages`() {
        val channel = purgeableChannel()
        countOption(10); userOption(99L)
        val theirs = message(authorId = 99L)
        historyOf(channel, listOf(message(authorId = 1L), theirs, message(authorId = 2L)))
        deletesSucceed(channel, single = theirs)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertEquals(listOf(theirs), deleted.single().toList())
    }

    @Test
    fun `one message reads as one message`() {
        val channel = purgeableChannel()
        countOption(1); userOption(null)
        val only = message()
        historyOf(channel, listOf(only))
        deletesSucceed(channel, single = only)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertTrue(replies.single().contains("Deleted 1 message."), replies.single())
    }

    @Test
    fun `messages past the fourteen-day line are left alone and accounted for`() {
        val channel = purgeableChannel()
        countOption(3); userOption(null)
        historyOf(channel, listOf(message(), message(), message(daysAgo = 40)))
        deletesSucceed(channel)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertEquals(2, deleted.single().size)
        assertTrue(replies.single().contains("1 older than 14 days skipped"), replies.single())
    }

    @Test
    fun `a scan of nothing but old messages deletes none and says why`() {
        val channel = purgeableChannel()
        countOption(5); userOption(null)
        historyOf(channel, listOf(message(daysAgo = 30), message(daysAgo = 30)))
        deletesSucceed(channel)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        assertTrue(replies.single().contains("2 older than 14 days"), replies.single())
    }

    @Test
    fun `a filter that matches nobody says so rather than blaming the age rule`() {
        val channel = purgeableChannel()
        countOption(5); userOption(99L)
        historyOf(channel, listOf(message(authorId = 1L), message(authorId = 2L)))
        deletesSucceed(channel)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        assertTrue(replies.single().contains("No matching messages"), replies.single())
    }

    @Test
    fun `a failed delete says why instead of claiming success`() {
        val channel = purgeableChannel()
        countOption(3); userOption(null)
        historyOf(channel, listOf(message(), message()))
        deletesFail(channel, "Missing Permissions")

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertTrue(replies.single().contains("Missing Permissions"), replies.single())
    }

    @Test
    fun `a history read that fails is reported rather than swallowed`() {
        val channel = purgeableChannel()
        countOption(3); userOption(null)
        historyFails(channel, "gateway said no")

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { channel.deleteMessages(any()) }
        assertTrue(replies.single().contains("gateway said no"), replies.single())
    }

    @Test
    fun `with no count given it scans ten`() {
        val channel = purgeableChannel()
        countOption(null); userOption(null)
        historyOf(channel, listOf(message()))
        deletesSucceed(channel, single = null)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertEquals(listOf(10), scanned)
    }

    @Test
    fun `a bot without Manage Messages is turned away before the channel is read`() {
        val channel = purgeableChannel()
        every { botMember.hasPermission(channel, Permission.MESSAGE_MANAGE) } returns false
        countOption(3); userOption(null)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { channel.history }
        assertTrue(replies.single().contains("I need Manage Messages"), replies.single())
    }

    @Test
    fun `a count of zero is refused before the channel is read`() {
        val channel = purgeableChannel()
        countOption(0); userOption(null)

        purgeCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { channel.history }
        assertTrue(replies.single().contains("between 1 and"), replies.single())
    }

    @Test
    fun `the option bounds match what Discord will actually bulk-delete`() {
        // The upper bound is typed by the user and enforced by Discord; if the
        // two drift, the command promises a purge the endpoint will refuse.
        val count = purgeCommand.optionData.single { it.name == "count" }

        assertEquals(1.0, count.minValue!!.toDouble())
        assertEquals(MessagePurge.MAX_BULK.toDouble(), count.maxValue!!.toDouble())
        assertEquals(OptionType.INTEGER, count.type)
        assertTrue(!count.isRequired)
    }

    @Test
    fun `the user option is optional, since purging everyone is the common case`() {
        val user = purgeCommand.optionData.single { it.name == "user" }

        assertEquals(OptionType.USER, user.type)
        assertTrue(!user.isRequired)
    }

    @Test
    fun `it is registered under the name the help text points at`() {
        assertEquals("purge", purgeCommand.name)
        assertTrue(purgeCommand.description.contains("100"), purgeCommand.description)
    }

    @Test
    fun `a caller the event cannot identify is dropped rather than guessed at`() {
        val ctx = mockk<DefaultCommandContext>(relaxed = true) {
            every { this@mockk.event } returns CommandTest.event
            every { this@mockk.member } returns null
        }

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 0) { event.hook.sendMessage(any<String>()) }
    }

}
