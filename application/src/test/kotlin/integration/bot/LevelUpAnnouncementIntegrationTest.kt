package integration.bot

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.user.UserDto
import database.service.leveling.XpAwardService
import database.service.user.UserService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-end guard for the level-up announcement pipeline:
 *
 *   XpAwardService.award (real, transactional)
 *     -> LevelUpEvent published in the Spring context
 *     -> every @EventListener (achievements, SSE, LevelUpListener) runs
 *     -> NotificationRouter.sendChannel resolves the route against the
 *        real ConfigService / UserNotificationPrefService (default
 *        opt-ins, no config rows) and posts to the origin channel.
 *
 * The unit suites mock the router or the listener, so none of them
 * would catch a wiring-level break (listener not scanned, a sibling
 * event listener throwing and rolling back the award, pref defaults
 * gating the channel post off, route resolution failing). This test
 * boots the full application context with the shared mocked JDA and
 * asserts the message actually reaches `TextChannel.sendMessage`.
 */
@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LevelUpAnnouncementIntegrationTest {

    @Autowired lateinit var xpAwardService: XpAwardService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var jda: JDA

    // High id space to avoid colliding with other integration tests' seeds.
    private val discordId = 9_100_001L
    private val guildId = 9_100_002L
    private val originChannelId = 9_100_003L

    @AfterEach
    fun cleanup() {
        runCatching { userService.deleteUserById(discordId, guildId) }
        userService.clearCache()
        // The JDA bean is shared across @SpringBootTest classes in this
        // module; drop our guild stub so later tests see the relaxed default.
        clearMocks(jda)
    }

    @Test
    fun `awarding XP across a level threshold posts the announcement to the origin channel`() {
        userService.clearCache()
        userService.createNewUser(UserDto(discordId, guildId))

        val sentPayload = slot<MessageCreateData>()
        // Real JDA returns `this` from mentionUsers; a relaxed mock would
        // hand back a child mock and queue() would land on that instead.
        val action = mockk<MessageCreateAction>(relaxed = true) {
            every { mentionUsers(*anyLongVararg()) } returns this@mockk
        }
        val channel = mockk<TextChannel>(relaxed = true) {
            every { sendMessage(capture(sentPayload)) } returns action
            every { name } returns "general"
            every { type } returns ChannelType.TEXT
        }
        val selfMember = mockk<SelfMember> {
            every { hasPermission(any<GuildChannel>(), *anyVararg<Permission>()) } returns true
        }
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
            every {
                hint(GuildMessageChannel::class)
                getChannelById(GuildMessageChannel::class.java, originChannelId)
            } returns channel
            every { this@mockk.selfMember } returns selfMember
            every { getMemberById(discordId) } returns null
        }
        every { jda.getGuildById(guildId) } returns guild

        // Level 0 -> 1 crosses at 100 XP (LevelCurve), well under the
        // 1000 XP default daily cap so the full amount lands.
        val granted = xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = 150L,
            reason = "integration-test",
            channelId = originChannelId,
        )
        assertEquals(150L, granted, "XP award should not be clamped or dropped")

        verify(exactly = 1) { channel.sendMessage(any<MessageCreateData>()) }
        val payload = sentPayload.captured
        assertEquals("<@$discordId>", payload.content) {
            "level-up post should carry the leveler's mention in content so the ping fires"
        }
        val embed = payload.embeds.single()
        assertTrue(embed.title!!.contains("LVL 1")) {
            "expected the new level in the embed title, got '${embed.title}'"
        }
        // The post must actually be dispatched, not just built.
        verify(exactly = 1) { action.queue(any(), any()) }
    }

    @Test
    fun `level-up earned inside a thread posts back into that thread`() {
        userService.clearCache()
        userService.createNewUser(UserDto(discordId, guildId))

        val action = mockk<MessageCreateAction>(relaxed = true) {
            every { mentionUsers(*anyLongVararg()) } returns this@mockk
        }
        val thread = mockk<ThreadChannel>(relaxed = true) {
            every { type } returns ChannelType.GUILD_PUBLIC_THREAD
            every { isArchived } returns false
            every { isLocked } returns false
            every { sendMessage(any<MessageCreateData>()) } returns action
        }
        val selfMember = mockk<SelfMember> {
            every { hasPermission(any<GuildChannel>(), *anyVararg<Permission>()) } returns true
        }
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
            every {
                hint(GuildMessageChannel::class)
                getChannelById(GuildMessageChannel::class.java, originChannelId)
            } returns thread
            every { this@mockk.selfMember } returns selfMember
            every { getMemberById(discordId) } returns null
        }
        every { jda.getGuildById(guildId) } returns guild

        xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = 150L,
            reason = "integration-test",
            channelId = originChannelId,
        )

        verify(exactly = 1) { thread.sendMessage(any<MessageCreateData>()) }
        verify(exactly = 1) { action.queue(any(), any()) }
    }

    @Test
    fun `award below the threshold posts nothing`() {
        userService.clearCache()
        userService.createNewUser(UserDto(discordId, guildId))

        val channel = mockk<TextChannel>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
            every { getTextChannelById(originChannelId) } returns channel
        }
        every { jda.getGuildById(guildId) } returns guild

        xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = 50L,
            reason = "integration-test",
            channelId = originChannelId,
        )

        verify(exactly = 0) { channel.sendMessage(any<MessageCreateData>()) }
    }
}
