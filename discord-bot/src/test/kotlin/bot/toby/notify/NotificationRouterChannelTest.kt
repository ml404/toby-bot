package bot.toby.notify

import common.notification.ChannelRouteKey
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import database.service.user.UserNotificationPrefService
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
        // mentionUsers(...) returns the action so the router can chain
        // .queue afterwards when mentions filtering is active. mockk
        // can't match a primitive `long...` vararg via `anyVararg<Long>()`
        // (that produces `Array<Long>`, JDA wants `LongArray`), so we
        // rely on `createAction`'s relaxed=true to return a relaxed
        // mock from any unstubbed mentionUsers call. Specific
        // mentionUsers stubs/verifies in the per-test cases below
        // pass an explicit `*longArrayOf(...)` spread.
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
    fun `does not check user notification prefs when no mentions are passed`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel

        router.sendChannel(guildId, ChannelRouteKey.LEVEL_UP, message = builder)

        // 4-arg signature matches `isOptedIn(discordId, guildId, kind, surface)`.
        verify(exactly = 0) { prefService.isOptedIn(any(), any(), any(), any()) }
        // And no mentionUsers call — when prefService isn't consulted,
        // the router doesn't take the filtering branch.
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

    // ---- mentions filtering (per-(kind, CHANNEL) opt-in) ----

    @Test
    fun `mentions with all users opted-in whitelist all ids via mentionUsers`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        val ids = listOf(10L, 20L, 30L)
        every {
            prefService.isOptedIn(any(), guildId, common.notification.NotificationChannelKind.TIP_RECEIVED, common.notification.Surface.CHANNEL)
        } returns true

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            mentions = ChannelMentions(common.notification.NotificationChannelKind.TIP_RECEIVED, ids),
        )

        // Spread an explicit LongArray to disambiguate from JDA's
        // `mentionUsers(vararg userIds: String)` overload.
        verify(exactly = 1) { createAction.mentionUsers(*longArrayOf(10L, 20L, 30L)) }
    }

    @Test
    fun `mentions with all users opted-out whitelists nothing (empty vararg)`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        val ids = listOf(10L, 20L)
        every {
            prefService.isOptedIn(any(), guildId, any(), common.notification.Surface.CHANNEL)
        } returns false

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            mentions = ChannelMentions(common.notification.NotificationChannelKind.TIP_RECEIVED, ids),
        )

        // Still called — mentionUsers with empty vararg restricts the
        // whitelist to nothing, so no users get pinged.
        // Spread empty LongArray to disambiguate from JDA's String vararg overload.
        verify(exactly = 1) { createAction.mentionUsers(*longArrayOf()) }
    }

    @Test
    fun `mentions with mixed opt-in filters to the opted-in subset only`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        every {
            prefService.isOptedIn(10L, guildId, any(), common.notification.Surface.CHANNEL)
        } returns true
        every {
            prefService.isOptedIn(20L, guildId, any(), common.notification.Surface.CHANNEL)
        } returns false
        every {
            prefService.isOptedIn(30L, guildId, any(), common.notification.Surface.CHANNEL)
        } returns true

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            mentions = ChannelMentions(
                common.notification.NotificationChannelKind.TIP_RECEIVED,
                listOf(10L, 20L, 30L),
            ),
        )

        // Only 10 and 30 — 20 dropped because they opted out.
        verify(exactly = 1) { createAction.mentionUsers(*longArrayOf(10L, 30L)) }
    }

    @Test
    fun `empty mentions list calls mentionUsers with empty vararg (defensive)`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            mentions = ChannelMentions(
                common.notification.NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
                emptyList(),
            ),
        )

        // The router calls mentionUsers() regardless when mentions is
        // non-null — same as "all opted out". Caller passes a non-null
        // ChannelMentions with empty list to signal "I want the
        // suppression policy applied, there just happen to be no users
        // to target right now".
        // Spread empty LongArray to disambiguate from JDA's String vararg overload.
        verify(exactly = 1) { createAction.mentionUsers(*longArrayOf()) }
    }

    @Test
    fun `mentions filtering uses the kind from ChannelMentions, not the route`() {
        stub(ConfigDto.Configurations.LEVEL_UP_CHANNEL, "1")
        every { guild.getTextChannelById(1L) } returns primaryChannel
        // Route is LEVEL_UP but mentions.kind is DUEL_OFFER — the pref
        // check must look up DUEL_OFFER, not LEVEL_UP.
        every {
            prefService.isOptedIn(10L, guildId, common.notification.NotificationChannelKind.DUEL_OFFER, common.notification.Surface.CHANNEL)
        } returns true

        router.sendChannel(
            guildId, ChannelRouteKey.LEVEL_UP,
            message = builder,
            mentions = ChannelMentions(
                common.notification.NotificationChannelKind.DUEL_OFFER,
                listOf(10L),
            ),
        )

        verify(exactly = 1) {
            prefService.isOptedIn(10L, guildId, common.notification.NotificationChannelKind.DUEL_OFFER, common.notification.Surface.CHANNEL)
        }
        verify(exactly = 1) { createAction.mentionUsers(*longArrayOf(10L)) }
    }
}
