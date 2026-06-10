package bot.toby.notify

import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushAdapter
import common.notification.PushPayload
import database.service.guild.ConfigService
import database.service.user.UserNotificationPrefService
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.event.WebPvpChallengeEvent

class WebPvpChallengeNotifierTest {

    private lateinit var router: NotificationRouter
    private lateinit var notifierJda: JDA
    private lateinit var notifier: WebPvpChallengeNotifier

    private val guildId = 42L
    private val initiatorId = 100L
    private val opponentId = 200L
    private val stake = 25L

    private fun event(game: String = "rock-paper-scissors") = WebPvpChallengeEvent(
        guildId = guildId,
        gameLabel = game,
        initiatorDiscordId = initiatorId,
        opponentDiscordId = opponentId,
        stake = stake,
    )

    @BeforeEach
    fun setup() {
        val routerJda = mockk<JDA>(relaxed = true)
        val prefService = mockk<UserNotificationPrefService>(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        router = spyk(NotificationRouter(routerJda, prefService, mockk<ConfigService>(relaxed = true), mockk<PushAdapter>(relaxed = true)))
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every { router.sendChannel(any(), any(), any(), any(), any(), any()) } just runs
        notifierJda = mockk {
            every { getGuildById(any<Long>()) } returns null
        }
        notifier = WebPvpChallengeNotifier(router, notifierJda, webBaseUrl = "https://example.test")
    }

    @Test
    fun `challenge routes to SYSTEM with the opponent mentioned`() {
        val mentions = slot<ChannelMentions>()
        every {
            router.sendChannel(
                guildId = any(), route = any(), originChannelId = any(),
                message = any(), onSent = any(), mentions = capture(mentions),
            )
        } just runs

        notifier.on(event())

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.SYSTEM,
                originChannelId = null,
                message = any(),
                onSent = any(),
                mentions = any(),
            )
        }
        assertEquals(NotificationChannelKind.PVP_CHALLENGE, mentions.captured.kind)
        assertEquals(listOf(opponentId), mentions.captured.userIds)
    }

    @Test
    fun `challenge lands in the opponent's voice channel when they're connected`() {
        val voiceChannel = mockk<net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion> {
            every { idLong } returns 777L
        }
        val voiceState = mockk<net.dv8tion.jda.api.entities.GuildVoiceState> {
            every { channel } returns voiceChannel
        }
        val member = mockk<net.dv8tion.jda.api.entities.Member> {
            every { getVoiceState() } returns voiceState
        }
        val guild = mockk<net.dv8tion.jda.api.entities.Guild> {
            every { getMemberById(opponentId) } returns member
        }
        every { notifierJda.getGuildById(guildId) } returns guild

        notifier.on(event())

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.SYSTEM,
                originChannelId = 777L,
                message = any(),
                onSent = any(),
                mentions = any(),
            )
        }
    }

    @Test
    fun `falls back to the initiator's voice channel when the opponent isn't in voice`() {
        val voiceChannel = mockk<net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion> {
            every { idLong } returns 888L
        }
        val voiceState = mockk<net.dv8tion.jda.api.entities.GuildVoiceState> {
            every { channel } returns voiceChannel
        }
        val initiatorMember = mockk<net.dv8tion.jda.api.entities.Member> {
            every { getVoiceState() } returns voiceState
        }
        val guild = mockk<net.dv8tion.jda.api.entities.Guild> {
            every { getMemberById(opponentId) } returns null
            every { getMemberById(initiatorId) } returns initiatorMember
        }
        every { notifierJda.getGuildById(guildId) } returns guild

        notifier.on(event())

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.SYSTEM,
                originChannelId = 888L,
                message = any(),
                onSent = any(),
                mentions = any(),
            )
        }
    }

    @Test
    fun `message content names the game and stake and pings both parties`() {
        val builder = slot<() -> MessageCreateData>()
        every {
            router.sendChannel(
                guildId = any(), route = any(), originChannelId = any(),
                message = capture(builder), onSent = any(), mentions = any(),
            )
        } just runs

        notifier.on(event(game = "connect 4"))

        val content = builder.captured.invoke().content
        assertTrue(content.contains("<@$opponentId>"))
        assertTrue(content.contains("<@$initiatorId>"))
        assertTrue(content.contains("connect 4"))
        assertTrue(content.contains("$stake"))
    }

    @Test
    fun `push payload deep-links to the guild's pvp page`() {
        val builder = slot<() -> PushPayload>()
        every { router.sendPush(any(), any(), any(), capture(builder)) } just runs

        notifier.on(event())

        verify(exactly = 1) {
            router.sendPush(opponentId, guildId, NotificationChannelKind.PVP_CHALLENGE, any())
        }
        val payload = builder.captured.invoke()
        assertEquals("https://example.test/pvp/$guildId", payload.deepLink)
        assertTrue(payload.title.contains("rock-paper-scissors"))
    }
}
