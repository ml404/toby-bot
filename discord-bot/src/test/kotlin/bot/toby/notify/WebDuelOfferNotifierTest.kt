package bot.toby.notify

import common.notification.ChannelRouteKey
import database.duel.PendingDuelRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.runs
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.event.WebDuelOfferedEvent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class WebDuelOfferNotifierTest {

    private lateinit var router: NotificationRouter
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var notifier: WebDuelOfferNotifier

    private val guildId = 42L
    private val duelId = 99L
    private val initiatorId = 100L
    private val opponentId = 200L
    private val stake = 50L
    private val sentMessageId = "1234567890"

    private fun event(): WebDuelOfferedEvent = WebDuelOfferedEvent(
        guildId = guildId,
        duelId = duelId,
        initiatorDiscordId = initiatorId,
        opponentDiscordId = opponentId,
        stake = stake
    )

    private val capturedTasks = mutableListOf<Runnable>()
    private val capturedDelays = mutableListOf<Long>()

    @BeforeEach
    fun setup() {
        router = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        every { pendingDuelRegistry.ttl } returns Duration.ofMinutes(3)
        scheduler = mockk(relaxed = true)
        capturedTasks.clear()
        capturedDelays.clear()
        every {
            scheduler.schedule(any<Runnable>(), any(), any<TimeUnit>())
        } answers {
            capturedTasks.add(firstArg())
            capturedDelays.add(secondArg<Long>())
            mockk(relaxed = true)
        }
        notifier = WebDuelOfferNotifier(router, pendingDuelRegistry, scheduler)
    }

    @Test
    fun `on routes the offer to SYSTEM with an onSent callback`() {
        notifier.on(event())

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.SYSTEM,
                originChannelId = null,
                message = any(),
                onSent = any(),
            )
        }
    }

    @Test
    fun `the built MessageCreateData carries the opponent ping in content`() {
        val builder = slot<() -> MessageCreateData>()
        every { router.sendChannel(any(), any(), any(), capture(builder), any()) } just runs

        notifier.on(event())

        val data = builder.captured.invoke()
        assertEquals(1, data.embeds.size, "embed should be present")
        assert(data.content.contains("<@$opponentId>")) {
            "Opponent mention must live in setContent, not the embed"
        }
        assertEquals(1, data.components.size, "ActionRow with Accept/Decline must be attached")
    }

    @Test
    fun `onSent callback schedules a TTL-aligned cleanup task`() {
        val onSent = slot<(Message) -> Unit>()
        every { router.sendChannel(any(), any(), any(), any(), capture(onSent)) } just runs

        notifier.on(event())
        val sent = fakeSentMessage(channelMock = mockk(relaxed = true))
        onSent.captured.invoke(sent)

        assertEquals(1, capturedTasks.size)
        assertEquals(Duration.ofMinutes(3).toMillis(), capturedDelays[0])
        verify(exactly = 1) {
            scheduler.schedule(any<Runnable>(), Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cleanup edits the message with the timeout embed when the offer is still pending`() {
        val channel: MessageChannelUnion = mockk(relaxed = true)
        val onSent = slot<(Message) -> Unit>()
        every { router.sendChannel(any(), any(), any(), any(), capture(onSent)) } just runs
        notifier.on(event())
        val sent = fakeSentMessage(channelMock = channel)
        onSent.captured.invoke(sent)

        val pending = PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = stake, createdAt = Instant.now()
        )
        every { pendingDuelRegistry.cancel(duelId) } returns pending

        val editAction = mockk<MessageEditAction>(relaxed = true)
        every { channel.editMessageEmbedsById(sentMessageId, any<MessageEmbed>()) } returns editAction
        every { editAction.setComponents(emptyList<MessageTopLevelComponent>()) } returns editAction

        capturedTasks[0].run()

        verify(exactly = 1) { pendingDuelRegistry.cancel(duelId) }
        verify(exactly = 1) { channel.editMessageEmbedsById(sentMessageId, any<MessageEmbed>()) }
        verify(exactly = 1) { editAction.setComponents(emptyList<MessageTopLevelComponent>()) }
        verify(exactly = 1) { editAction.queue() }
    }

    @Test
    fun `cleanup is a no-op when the offer was already accepted or declined`() {
        val channel: MessageChannelUnion = mockk(relaxed = true)
        val onSent = slot<(Message) -> Unit>()
        every { router.sendChannel(any(), any(), any(), any(), capture(onSent)) } just runs
        notifier.on(event())
        onSent.captured.invoke(fakeSentMessage(channelMock = channel))

        every { pendingDuelRegistry.cancel(duelId) } returns null

        capturedTasks[0].run()

        verify(exactly = 1) { pendingDuelRegistry.cancel(duelId) }
        verify(exactly = 0) { channel.editMessageEmbedsById(any<String>(), any<MessageEmbed>()) }
    }

    @Test
    fun `cleanup swallows JDA exceptions so a failing edit does not kill the scheduler thread`() {
        val channel: MessageChannelUnion = mockk(relaxed = true)
        val onSent = slot<(Message) -> Unit>()
        every { router.sendChannel(any(), any(), any(), any(), capture(onSent)) } just runs
        notifier.on(event())
        onSent.captured.invoke(fakeSentMessage(channelMock = channel))

        every { pendingDuelRegistry.cancel(duelId) } returns PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = stake, createdAt = Instant.now()
        )
        every {
            channel.editMessageEmbedsById(sentMessageId, any<MessageEmbed>())
        } throws RuntimeException("kapow")

        // Must not propagate — scheduler thread would die otherwise.
        capturedTasks[0].run()
    }

    private fun fakeSentMessage(channelMock: MessageChannelUnion): Message = mockk(relaxed = true) {
        every { id } returns sentMessageId
        every { channel } returns channelMock
    }
}
