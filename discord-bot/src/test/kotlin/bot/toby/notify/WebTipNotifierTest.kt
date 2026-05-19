package bot.toby.notify

import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import database.service.ConfigService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.event.WebTipSentEvent

class WebTipNotifierTest {

    private lateinit var router: NotificationRouter
    private lateinit var notifier: WebTipNotifier

    private val guildId = 42L
    private val senderId = 100L
    private val recipientId = 200L

    private fun event(): WebTipSentEvent = WebTipSentEvent(
        guildId = guildId,
        senderDiscordId = senderId,
        recipientDiscordId = recipientId,
        amount = 50L,
        note = "thanks",
        senderNewBalance = 950L,
        recipientNewBalance = 1050L,
        sentTodayAfter = 50L,
        dailyCap = 500L
    )

    @BeforeEach
    fun setup() {
        val jda = mockk<JDA>(relaxed = true)
        val prefService = mockk<UserNotificationPrefService>(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        val configService = mockk<ConfigService>(relaxed = true)
        val pushAdapter = mockk<PushAdapter>(relaxed = true)
        router = spyk(NotificationRouter(jda, prefService, configService, pushAdapter))
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } just runs
        notifier = WebTipNotifier(router)
    }

    @Test
    fun `on routes to SYSTEM with the correct guildId and TIP_RECEIVED mentions`() {
        val mentionsSlot = slot<ChannelMentions>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = any(),
                onSent = any(),
                mentions = capture(mentionsSlot),
            )
        } answers { }

        notifier.on(event())

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.SYSTEM,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = any(),
            )
        }
        // The router gets a non-null mentions struct carrying the
        // recipient so it can suppress their user-ping if they've
        // opted out of (TIP_RECEIVED, CHANNEL).
        val mentions = mentionsSlot.captured
        assertEquals(NotificationChannelKind.TIP_RECEIVED, mentions.kind)
        assertEquals(listOf(recipientId), mentions.userIds)
    }

    @Test
    fun `the lazy message includes the recipient ping in content (not the embed)`() {
        val builder = slot<() -> MessageCreateData>()
        every {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = capture(builder),
                onSent = any(),
                mentions = any(),
            )
        } just runs

        notifier.on(event())

        val data: MessageCreateData = builder.captured.invoke()
        assertNotNull(data)
        assertTrue(
            data.content.contains("<@$recipientId>"),
            "Recipient mention must live in setContent, not the embed; embed-mentions don't ping."
        )
        assertEquals(1, data.embeds.size)
    }

    @Test
    fun `on also pushes the recipient — regression guard for forgotten push surface`() {
        // TIP_RECEIVED supports CHANNEL + PUSH. Dispatch enforcement now
        // requires both; this pins that the push surface fires alongside
        // the channel post for opted-in recipients.
        notifier.on(event())
        verify(exactly = 1) {
            router.sendPush(recipientId, guildId, NotificationChannelKind.TIP_RECEIVED, any())
        }
    }

    @Test
    fun `push payload carries amount and quotes the note when present`() {
        val builder = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(builder))
        } just runs

        notifier.on(event())

        val payload = builder.captured.invoke()
        assertTrue(payload.title.contains("50")) {
            "expected amount in the push title, got: ${payload.title}"
        }
        assertTrue(payload.body.contains("thanks")) {
            "expected the note in the push body, got: ${payload.body}"
        }
    }

    @Test
    fun `push payload omits the note when blank or absent`() {
        val noNote = event().copy(note = null)
        val builder = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(builder))
        } just runs

        notifier.on(noNote)

        val payload = builder.captured.invoke()
        assertTrue(!payload.body.contains("\"")) {
            "expected no quoted note when event.note is null, got: ${payload.body}"
        }
    }

    @Test
    fun `push payload has no deepLink when app base-url is unconfigured`() {
        // Default constructor uses an empty base-url.
        val builder = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(builder))
        } just runs

        notifier.on(event())

        assertNull(builder.captured.invoke().deepLink)
    }

    @Test
    fun `push payload deep-links to the profile page when app base-url is configured`() {
        val configured = WebTipNotifier(router, webBaseUrl = "https://example.test")
        val builder = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(builder))
        } just runs

        configured.on(event())

        assertEquals("https://example.test/profile/$guildId", builder.captured.invoke().deepLink)
    }
}
