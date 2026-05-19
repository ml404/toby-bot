package bot.toby.notify

import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
        router = mockk(relaxed = true)
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
        } answers { }

        notifier.on(event())

        val data: MessageCreateData = builder.captured.invoke()
        assertNotNull(data)
        assertTrue(
            data.content.contains("<@$recipientId>"),
            "Recipient mention must live in setContent, not the embed; embed-mentions don't ping."
        )
        assertEquals(1, data.embeds.size)
    }
}
