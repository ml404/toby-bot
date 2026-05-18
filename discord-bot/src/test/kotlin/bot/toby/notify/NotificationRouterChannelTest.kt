package bot.toby.notify

import common.notification.ChannelRouteKey
import database.dto.ConfigDto
import database.service.ConfigService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Channel-routing coverage for [NotificationRouter.sendChannel]. The
 * DM side ([NotificationRouter.sendDm]) was previously the only
 * behaviour and continues to live alongside this.
 *
 * Resolution chain the router applies:
 *   1. primaryConfigKey
 *   2. each fallbackConfigKeys in order
 *   3. originChannelId
 *   4. guild.systemChannel (if route allows)
 *
 * Channel posts are NOT gated by user notification prefs.
 */
class NotificationRouterChannelTest {

    private val guildId = 100L
    private lateinit var jda: JDA
    private lateinit var configService: ConfigService
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var guild: Guild
    private lateinit var bot: SelfMember
    private lateinit var router: NotificationRouter

    private lateinit var primaryChannel: TextChannel
    private lateinit var fallbackChannel: TextChannel
    private lateinit var originChannel: TextChannel
    private lateinit var systemChannel: TextChannel
    private lateinit var createAction: MessageCreateAction

    private val payload = MessageCreateBuilder().setContent("hello").build()
    private val builder: () -> MessageCreateData = { payload }

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        bot = mockk(relaxed = true)
        primaryChannel = mockk(relaxed = true)
        fallbackChannel = mockk(relaxed = true)
        originChannel = mockk(relaxed = true)
        systemChannel = mockk(relaxed = true)
        createAction = mockk(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.idLong } returns guildId
        every { guild.selfMember } returns bot
        every { bot.hasPermission(any<TextChannel>(), *anyVararg<Permission>()) } returns true

        every { primaryChannel.idLong } returns 1L
        every { fallbackChannel.idLong } returns 2L
        every { originChannel.idLong } returns 3L
        every { systemChannel.idLong } returns 4L
        every { primaryChannel.name } returns "primary"
        every { fallbackChannel.name } returns "fallback"
        every { originChannel.name } returns "origin"
        every { systemChannel.name } returns "system"

        // Each channel returns the same create-action mock (the router
        // doesn't care which channel produced the action; we only verify
        // which channel was asked to sendMessage).
        every { primaryChannel.sendMessage(any<MessageCreateData>()) } returns createAction
        every { fallbackChannel.sendMessage(any<MessageCreateData>()) } returns createAction
        every { originChannel.sendMessage(any<MessageCreateData>()) } returns createAction
        every { systemChannel.sendMessage(any<MessageCreateData>()) } returns createAction
        every {
            createAction.queue(any<Consumer<Message>>(), any<Consumer<in Throwable>>())
        } just runs

        router = NotificationRouter(jda, prefService, configService)
    }

    private fun stub(key: ConfigDto.Configurations, value: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns value?.let {
            ConfigDto(name = key.configValue, value = it, guildId = guildId.toString())
        }
    }

    @Test
    fun `resolves primaryConfigKey first`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        verify(exactly = 1) { primaryChannel.sendMessage(payload) }
        verify(exactly = 0) { fallbackChannel.sendMessage(any<MessageCreateData>()) }
    }

    @Test
    fun `falls through to fallbackConfigKeys when primary is unset`() {
        // LOTTERY route: primary=LOTTERY_CHANNEL, fallback=LEADERBOARD_CHANNEL.
        stub(ConfigDto.Configurations.LOTTERY_CHANNEL, null)
        stub(ConfigDto.Configurations.LEADERBOARD_CHANNEL, "2")
        every { guild.getTextChannelById(2L) } returns fallbackChannel

        router.sendChannel(guildId, ChannelRouteKey.LOTTERY, message = builder)

        verify(exactly = 1) { fallbackChannel.sendMessage(payload) }
    }

    @Test
    fun `falls through to originChannelId when configs miss`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, null)
        every { guild.getTextChannelById(3L) } returns originChannel

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            originChannelId = 3L,
            message = builder,
        )

        verify(exactly = 1) { originChannel.sendMessage(payload) }
    }

    @Test
    fun `falls through to system channel when route allows and earlier steps miss`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, null)
        every { guild.systemChannel } returns systemChannel

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        verify(exactly = 1) { systemChannel.sendMessage(payload) }
    }

    @Test
    fun `ACHIEVEMENT_SHOUTOUT does not fall through to system channel`() {
        stub(ConfigDto.Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL, null)
        every { guild.systemChannel } returns systemChannel  // would resolve if route allowed

        router.sendChannel(guildId, ChannelRouteKey.ACHIEVEMENT_SHOUTOUT, message = builder)

        verify(exactly = 0) { systemChannel.sendMessage(any<MessageCreateData>()) }
    }

    @Test
    fun `silently no-ops when nothing resolves`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, null)
        every { guild.systemChannel } returns null

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        verify(exactly = 0) { primaryChannel.sendMessage(any<MessageCreateData>()) }
        verify(exactly = 0) { fallbackChannel.sendMessage(any<MessageCreateData>()) }
        verify(exactly = 0) { systemChannel.sendMessage(any<MessageCreateData>()) }
    }

    @Test
    fun `skips channels the bot cannot post in and continues the chain`() {
        stub(ConfigDto.Configurations.LOTTERY_CHANNEL, "1")
        stub(ConfigDto.Configurations.LEADERBOARD_CHANNEL, "2")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        every { guild.getTextChannelById(2L) } returns fallbackChannel
        every { bot.hasPermission(primaryChannel, *anyVararg<Permission>()) } returns false
        every { bot.hasPermission(fallbackChannel, *anyVararg<Permission>()) } returns true

        router.sendChannel(guildId, ChannelRouteKey.LOTTERY, message = builder)

        verify(exactly = 0) { primaryChannel.sendMessage(any<MessageCreateData>()) }
        verify(exactly = 1) { fallbackChannel.sendMessage(payload) }
    }

    @Test
    fun `does not check user notification prefs for channel routing`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        verify(exactly = 0) { prefService.isOptedIn(any(), any(), any()) }
    }

    @Test
    fun `onSent callback receives the sent message after dispatch`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        val sent = mockk<Message>(relaxed = true)
        val successSlot = slot<Consumer<Message>>()
        every { createAction.queue(capture(successSlot), any<Consumer<in Throwable>>()) } just runs

        var captured: Message? = null
        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            onSent = { msg -> captured = msg },
        )
        successSlot.captured.accept(sent)

        assertSame(sent, captured)
    }

    @Test
    fun `returns silently when guild is not in JDA`() {
        every { jda.getGuildById(guildId) } returns null

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        verify(exactly = 0) { primaryChannel.sendMessage(any<MessageCreateData>()) }
    }
}
