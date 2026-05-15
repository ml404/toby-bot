package bot.toby.notify

import database.duel.PendingDuelRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import web.event.WebDuelOfferedEvent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class WebDuelOfferNotifierTest {

    private lateinit var jda: JDA
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var guild: Guild
    private lateinit var channel: TextChannel
    private lateinit var createAction: MessageCreateAction
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

    // Test scheduler that captures scheduled runnables so individual cases
    // can fire (or skip) the TTL cleanup deterministically — much faster
    // than waiting 3 minutes of wall-clock TTL.
    private val capturedTasks = mutableListOf<Runnable>()
    private val capturedDelays = mutableListOf<Long>()

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        every { pendingDuelRegistry.ttl } returns Duration.ofMinutes(3)
        guild = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        createAction = mockk(relaxed = true)
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

        every { jda.getGuildById(guildId) } returns guild
        every { guild.systemChannel } returns channel
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns true
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { createAction.addContent(any()) } returns createAction
        every { createAction.addComponents(any<ActionRow>()) } returns createAction
        notifier = WebDuelOfferNotifier(jda, pendingDuelRegistry, scheduler)
    }

    private fun fakeSentMessage(): Message = mockk(relaxed = true) {
        every { id } returns sentMessageId
    }

    @Test
    fun `happy path posts the embed and chains addContent for the opponent ping`() {
        notifier.on(event())

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { createAction.addContent("<@$opponentId>") }
    }

    @Test
    fun `skips when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips when guild has no system channel`() {
        every { guild.systemChannel } returns null

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips when bot lacks permission on the system channel`() {
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns false

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // Capture the .queue { sent -> ... } callback so we can invoke it
    // with a fake sent message and drive the cleanup branch.
    private fun fireSendCallbackWithMessage(): Message {
        val callback = slot<Consumer<Message>>()
        every { createAction.queue(capture(callback)) } just runs
        notifier.on(event())
        val sent = fakeSentMessage()
        callback.captured.accept(sent)
        return sent
    }

    @Test
    fun `schedules a TTL-aligned cleanup task once the message is sent`() {
        fireSendCallbackWithMessage()

        // One task, scheduled at the registry's configured TTL (3 minutes).
        assertEquals(1, capturedTasks.size)
        assertEquals(Duration.ofMinutes(3).toMillis(), capturedDelays[0])
        verify(exactly = 1) {
            scheduler.schedule(any<Runnable>(), Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cleanup edits the message with the timeout embed when the offer is still pending`() {
        fireSendCallbackWithMessage()
        val pending = PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = stake, createdAt = Instant.now()
        )
        // Win the race — registry hands us the offer.
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
        fireSendCallbackWithMessage()
        // Lose the race — accept/decline already removed the offer.
        every { pendingDuelRegistry.cancel(duelId) } returns null

        capturedTasks[0].run()

        verify(exactly = 1) { pendingDuelRegistry.cancel(duelId) }
        verify(exactly = 0) { channel.editMessageEmbedsById(any<String>(), any<MessageEmbed>()) }
    }

    @Test
    fun `cleanup swallows JDA exceptions so a failing edit does not propagate`() {
        fireSendCallbackWithMessage()
        val pending = PendingDuelRegistry.PendingDuel(
            id = duelId, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = stake, createdAt = Instant.now()
        )
        every { pendingDuelRegistry.cancel(duelId) } returns pending
        // editMessageEmbedsById throws — could happen if the message was
        // deleted, the bot lost permission, or the channel went away.
        every {
            channel.editMessageEmbedsById(sentMessageId, any<MessageEmbed>())
        } throws RuntimeException("kapow")

        // Should not propagate — the scheduler thread would otherwise die.
        capturedTasks[0].run()
    }
}
