package bot.toby.notify

import common.events.AntiAutoclickEvent
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class AntiAutoclickNotifierTest {

    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var selfMember: SelfMember
    private lateinit var systemChannel: TextChannel
    private lateinit var configChannel: TextChannel
    private lateinit var configService: ConfigService
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var notifier: AntiAutoclickNotifier

    private lateinit var createAction: MessageCreateAction
    private lateinit var editAction: MessageEditAction

    private val guildId = 42L
    private val systemChannelId = 1000L
    private val configChannelId = 2000L
    private val discordId = 100L
    private val gameKey = "dice"
    private val openMessageId = 555_000L

    private val baseInstant: Instant = Instant.parse("2026-05-09T12:00:00Z")
    private var nowOffset: Long = 0
    private val advanceableClock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = baseInstant.plusSeconds(nowOffset)
    }

    private val pendingScheduledTasks = mutableListOf<Runnable>()
    // Captures the Consumer<Message> handed to createAction.queue(...) on
    // every send. Each open() call overwrites it; the
    // completeOpenSendWithMessageId helper invokes the latest one to
    // simulate JDA's REST callback firing.
    private val openConsumerSlot = slot<Consumer<Message>>()

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        selfMember = mockk(relaxed = true)
        systemChannel = mockk(relaxed = true)
        configChannel = mockk(relaxed = true)
        configService = mockk(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.selfMember } returns selfMember
        every { guild.systemChannel } returns systemChannel
        every { guild.getTextChannelById(configChannelId) } returns configChannel

        every { systemChannel.idLong } returns systemChannelId
        every { configChannel.idLong } returns configChannelId
        every { selfMember.hasPermission(systemChannel, *anyVararg<Permission>()) } returns true
        every { selfMember.hasPermission(configChannel, *anyVararg<Permission>()) } returns true

        // Default: no configured mod-log channel → fall back to system channel.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
                guildId.toString(),
            )
        } returns null

        // Send: stub the queue() Consumer overload to capture the message-id
        // callback into openConsumerSlot every time, so the test can synchronously
        // simulate JDA's REST callback handing back a Message. Setting the
        // capture in the stub (not in a verify) means the slot tracks the
        // latest call and survives multiple opens within a single test.
        createAction = mockk(relaxed = true)
        every { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { createAction.queue(capture(openConsumerSlot)) } just runs

        editAction = mockk(relaxed = true)
        every {
            systemChannel.editMessageEmbedsById(any<Long>(), any<MessageEmbed>())
        } returns editAction
        every {
            configChannel.editMessageEmbedsById(any<Long>(), any<MessageEmbed>())
        } returns editAction
        every { jda.getTextChannelById(systemChannelId) } returns systemChannel
        every { jda.getTextChannelById(configChannelId) } returns configChannel

        scheduler = mockk(relaxed = true)
        // Capture each scheduled Runnable into a list so the test can flush
        // them deterministically. Return a relaxed ScheduledFuture so the
        // notifier's CAS/cancel calls succeed.
        every {
            scheduler.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        } answers {
            pendingScheduledTasks += firstArg<Runnable>()
            mockk<ScheduledFuture<*>>(relaxed = true)
        }

        notifier = AntiAutoclickNotifier(jda, configService, advanceableClock, scheduler)
    }

    /** Simulate JDA's REST callback completing — feeds the most recently
     *  captured Consumer a stub Message with the given idLong. */
    private fun completeOpenSendWithMessageId(id: Long) {
        val msg = mockk<Message>(relaxed = true).also { every { it.idLong } returns id }
        openConsumerSlot.captured.accept(msg)
    }

    private fun openEvent(streak: Int = 1) =
        AntiAutoclickEvent.SessionOpened(guildId, discordId, gameKey, streak)

    private fun firedEvent(streak: Int, edgePct: Double = streak * 2.5) =
        AntiAutoclickEvent.BiasFired(guildId, discordId, gameKey, streak, edgePct)

    private fun closedEvent() =
        AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)

    // -------- SessionOpened --------

    @Test
    fun `SessionOpened sends a new embed to the system channel by default`() {
        notifier.onOpened(openEvent())

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { configChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `SessionOpened captures the message id from the queue callback into the session snapshot`() {
        notifier.onOpened(openEvent())

        completeOpenSendWithMessageId(openMessageId)

        val snapshot = notifier.sessionSnapshotForTest(discordId, guildId, gameKey)
        assertNotNull(snapshot)
        assertEquals(openMessageId, snapshot!!.messageId)
        assertEquals(systemChannelId, snapshot.channelId)
        assertEquals(1, snapshot.currentStreak)
        assertEquals(1, snapshot.peakStreak)
        assertEquals(0, snapshot.fireCount)
    }

    @Test
    fun `SessionOpened skips silently when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null

        notifier.onOpened(openEvent())

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { configChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        assertNull(notifier.sessionSnapshotForTest(discordId, guildId, gameKey))
    }

    @Test
    fun `SessionOpened skips when guild has no usable channel at all`() {
        every { guild.systemChannel } returns null

        notifier.onOpened(openEvent())

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        assertNull(notifier.sessionSnapshotForTest(discordId, guildId, gameKey))
    }

    @Test
    fun `SessionOpened skips when bot lacks permission on the system channel`() {
        every { selfMember.hasPermission(systemChannel, *anyVararg<Permission>()) } returns false

        notifier.onOpened(openEvent())

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // -------- Channel resolution --------

    @Test
    fun `prefers configured CASINO_MODLOG_CHANNEL_ID over the system channel`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
                guildId.toString(),
            )
        } returns ConfigDto(name = "x", value = configChannelId.toString(), guildId = guildId.toString())

        notifier.onOpened(openEvent())

        verify(exactly = 1) { configChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `falls back to system channel when configured channel id is unparseable`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
                guildId.toString(),
            )
        } returns ConfigDto(name = "x", value = "not-a-number", guildId = guildId.toString())

        notifier.onOpened(openEvent())

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `falls back to system channel when configured channel id does not resolve to a text channel`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
                guildId.toString(),
            )
        } returns ConfigDto(name = "x", value = "9999", guildId = guildId.toString())
        every { guild.getTextChannelById(9999L) } returns null

        notifier.onOpened(openEvent())

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `falls back to system channel when bot lacks permission on the configured channel`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID.configValue,
                guildId.toString(),
            )
        } returns ConfigDto(name = "x", value = configChannelId.toString(), guildId = guildId.toString())
        every { selfMember.hasPermission(configChannel, *anyVararg<Permission>()) } returns false

        notifier.onOpened(openEvent())

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { configChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // -------- BiasFired --------

    @Test
    fun `BiasFired with no active session is a silent no-op (post-restart safety)`() {
        notifier.onFired(firedEvent(streak = 5))

        verify(exactly = 0) {
            scheduler.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        }
        verify(exactly = 0) {
            systemChannel.editMessageEmbedsById(any<Long>(), any<MessageEmbed>())
        }
    }

    @Test
    fun `first BiasFired after open schedules a debounced edit and bumps counters`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)
        nowOffset = 5

        notifier.onFired(firedEvent(streak = 3, edgePct = 7.5))

        verify(exactly = 1) {
            scheduler.schedule(any<Runnable>(), AntiAutoclickNotifier.EDIT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
        val snap = notifier.sessionSnapshotForTest(discordId, guildId, gameKey)!!
        assertEquals(1, snap.fireCount)
        assertEquals(3, snap.currentStreak)
        assertEquals(3, snap.peakStreak)
        assertEquals(7.5, snap.edgePct, 0.0001)
    }

    @Test
    fun `multiple BiasFired events inside the debounce window coalesce into one scheduled edit`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)

        notifier.onFired(firedEvent(streak = 3))
        notifier.onFired(firedEvent(streak = 4))
        notifier.onFired(firedEvent(streak = 5))

        // Three fires, ONE scheduled edit task.
        verify(exactly = 1) {
            scheduler.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        }
        val snap = notifier.sessionSnapshotForTest(discordId, guildId, gameKey)!!
        assertEquals(3, snap.fireCount)
        assertEquals(5, snap.currentStreak)
        assertEquals(5, snap.peakStreak)
    }

    @Test
    fun `peak streak tracks the maximum even when current streak retreats`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)

        notifier.onFired(firedEvent(streak = 8))
        notifier.onFired(firedEvent(streak = 12))
        notifier.onFired(firedEvent(streak = 7))

        val snap = notifier.sessionSnapshotForTest(discordId, guildId, gameKey)!!
        assertEquals(7, snap.currentStreak)
        assertEquals(12, snap.peakStreak)
    }

    @Test
    fun `flushing the debounced task issues exactly one edit reflecting the latest counters`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)

        notifier.onFired(firedEvent(streak = 4))
        notifier.onFired(firedEvent(streak = 6))
        // Run the captured debouncer task — simulates the 2s window elapsing.
        assertEquals(1, pendingScheduledTasks.size)
        pendingScheduledTasks.first().run()

        verify(exactly = 1) {
            systemChannel.editMessageEmbedsById(openMessageId, any<MessageEmbed>())
        }
    }

    @Test
    fun `after the debounced edit fires, a subsequent BiasFired schedules a fresh edit (no leak)`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)

        notifier.onFired(firedEvent(streak = 3))
        pendingScheduledTasks.first().run()
        pendingScheduledTasks.clear()

        notifier.onFired(firedEvent(streak = 4))

        // First scheduling + second scheduling = 2 schedule calls total.
        verify(exactly = 2) {
            scheduler.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        }
    }

    // -------- SessionClosed --------

    @Test
    fun `SessionClosed cancels any pending edit and writes one final summary edit`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)
        notifier.onFired(firedEvent(streak = 5))
        nowOffset = 30

        notifier.onClosed(closedEvent())

        verify(exactly = 1) {
            systemChannel.editMessageEmbedsById(openMessageId, any<MessageEmbed>())
        }
        // Session is removed from the map.
        assertNull(notifier.sessionSnapshotForTest(discordId, guildId, gameKey))
    }

    @Test
    fun `SessionClosed with no active session is a silent no-op`() {
        notifier.onClosed(closedEvent())

        verify(exactly = 0) {
            systemChannel.editMessageEmbedsById(any<Long>(), any<MessageEmbed>())
        }
    }

    @Test
    fun `SessionClosed before the open send-callback returned a message id does not edit`() {
        // Open the session but do NOT invoke the queue callback — the
        // message id never landed. SessionClosed should not blow up; it
        // just removes the session entry without an edit attempt.
        notifier.onOpened(openEvent())

        notifier.onClosed(closedEvent())

        verify(exactly = 0) {
            systemChannel.editMessageEmbedsById(any<Long>(), any<MessageEmbed>())
        }
        assertNull(notifier.sessionSnapshotForTest(discordId, guildId, gameKey))
    }

    @Test
    fun `SessionClosed after fires posts the summary even if the debounce never flushed`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)
        notifier.onFired(firedEvent(streak = 5))
        // Don't run the pending scheduled task — the session closes first.

        notifier.onClosed(closedEvent())

        // Exactly one edit (the closed-summary), not the active-edit.
        verify(exactly = 1) {
            systemChannel.editMessageEmbedsById(openMessageId, any<MessageEmbed>())
        }
    }

    // -------- Multi-session isolation --------

    @Test
    fun `two different gameKeys for the same user are independent sessions`() {
        val openDice = AntiAutoclickEvent.SessionOpened(guildId, discordId, "dice", 1)
        val openSlots = AntiAutoclickEvent.SessionOpened(guildId, discordId, "slots", 1)
        val firedDice = AntiAutoclickEvent.BiasFired(guildId, discordId, "dice", 8, 20.0)

        notifier.onOpened(openDice)
        notifier.onOpened(openSlots)
        notifier.onFired(firedDice)

        // Two separate send calls (one per game) and one schedule (only dice fired).
        verify(exactly = 2) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            scheduler.schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
        }
        val diceSnap = notifier.sessionSnapshotForTest(discordId, guildId, "dice")!!
        val slotsSnap = notifier.sessionSnapshotForTest(discordId, guildId, "slots")!!
        assertEquals(1, diceSnap.fireCount)
        assertEquals(0, slotsSnap.fireCount)
    }

    @Test
    fun `re-opening after a close starts a fresh session with cleared counters`() {
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)
        notifier.onFired(firedEvent(streak = 7))
        notifier.onClosed(closedEvent())

        // Second session for the same key — fresh map entry, fresh counters.
        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId + 1)

        val snap = notifier.sessionSnapshotForTest(discordId, guildId, gameKey)!!
        assertEquals(openMessageId + 1, snap.messageId)
        assertEquals(0, snap.fireCount)
        assertEquals(1, snap.peakStreak)
        // Two open sends total.
        verify(exactly = 2) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `flushPendingEditForTest helper runs only when there is a pending edit`() {
        // No session yet → false.
        assertEquals(false, notifier.flushPendingEditForTest(discordId, guildId, gameKey))

        notifier.onOpened(openEvent())
        completeOpenSendWithMessageId(openMessageId)
        // Session but no fires → no pending edit → false.
        assertEquals(false, notifier.flushPendingEditForTest(discordId, guildId, gameKey))

        notifier.onFired(firedEvent(streak = 3))
        // Now there is a pending edit → flush succeeds.
        assertTrue(notifier.flushPendingEditForTest(discordId, guildId, gameKey))
        verify(exactly = 1) {
            systemChannel.editMessageEmbedsById(openMessageId, any<MessageEmbed>())
        }
    }
}
