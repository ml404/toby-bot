package bot.toby.activity

import common.events.activity.ActivityTrackingEnabled
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ActivityTrackingNotifier].
 *
 * JDA and service deps are mocked with `relaxed = true` so JDA's
 * deeply nested interface graph doesn't require exhaustive stubbing.
 * No Spring context, DB, or network is required.
 */
class ActivityTrackingNotifierTest {

    private val guildId = 42L
    private val guildIdStr = guildId.toString()

    private lateinit var configService: ConfigService
    private lateinit var jda: JDA
    private lateinit var notifier: ActivityTrackingNotifier

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        notifier = ActivityTrackingNotifier(configService, jda)
    }

    // ---- onActivityTrackingEnabled ----

    @Test
    fun `onActivityTrackingEnabled skips when JDA has no matching guild`() {
        every { jda.getGuildById(guildId) } returns null

        notifier.onActivityTrackingEnabled(ActivityTrackingEnabled(guildId))

        // No config read or upsert should happen when the guild is unknown.
        verify(exactly = 0) { configService.getConfigByName(any(), any()) }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `onActivityTrackingEnabled delegates to notifyMembersOnFirstEnable when guild is found`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every { jda.getGuildById(guildId) } returns guild
        // Already notified — short-circuits before any DM.
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns ConfigDto(
            name = ACTIVITY_TRACKING_NOTIFIED.configValue,
            value = "true",
            guildId = guildIdStr
        )

        notifier.onActivityTrackingEnabled(ActivityTrackingEnabled(guildId))

        // The already-notified guard fires, so upsertConfig is not called again.
        verify(exactly = 1) {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    // ---- notifyMembersOnFirstEnable ----

    @Test
    fun `notifyMembersOnFirstEnable skips DMs when already notified flag is true`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns ConfigDto(
            name = ACTIVITY_TRACKING_NOTIFIED.configValue,
            value = "true",
            guildId = guildIdStr
        )

        notifier.notifyMembersOnFirstEnable(guild)

        // Short-circuits before iterating members.
        verify(exactly = 0) { guild.members }
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `notifyMembersOnFirstEnable case-insensitively detects the true flag`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        // Stored with capital T — should still be treated as already-notified.
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns ConfigDto(
            name = ACTIVITY_TRACKING_NOTIFIED.configValue,
            value = "TRUE",
            guildId = guildIdStr
        )

        notifier.notifyMembersOnFirstEnable(guild)

        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `notifyMembersOnFirstEnable upserts notified flag after DMing members when not previously notified`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Server"
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns null
        // No members — DM loop is a no-op, but the flag must still be written.
        every { guild.members } returns emptyList()

        notifier.notifyMembersOnFirstEnable(guild)

        verify(exactly = 1) {
            configService.upsertConfig(ACTIVITY_TRACKING_NOTIFIED.configValue, "true", guildIdStr)
        }
    }

    @Test
    fun `notifyMembersOnFirstEnable sends DMs to non-bot members only`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Server"
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns null

        val humanUser = mockk<User>(relaxed = true)
        every { humanUser.isBot } returns false
        every { humanUser.id } returns "1"

        val botUser = mockk<User>(relaxed = true)
        every { botUser.isBot } returns true
        every { botUser.id } returns "2"

        val humanMember = mockk<Member>(relaxed = true)
        every { humanMember.user } returns humanUser

        val botMember = mockk<Member>(relaxed = true)
        every { botMember.user } returns botUser

        every { guild.members } returns listOf(humanMember, botMember)

        notifier.notifyMembersOnFirstEnable(guild)

        // The human member's private channel is opened; the bot's is not.
        verify(exactly = 1) { humanUser.openPrivateChannel() }
        verify(exactly = 0) { botUser.openPrivateChannel() }
    }

    @Test
    fun `notifyMembersOnFirstEnable skips DMs when flag value is false but upserts at end`() {
        // A guild that previously had tracking disabled (value="false") has
        // just enabled it — should proceed with first-enable flow.
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Server"
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns ConfigDto(
            name = ACTIVITY_TRACKING_NOTIFIED.configValue,
            value = "false",
            guildId = guildIdStr
        )
        every { guild.members } returns emptyList()

        notifier.notifyMembersOnFirstEnable(guild)

        // "false" is NOT the short-circuit condition — upsert happens.
        verify(exactly = 1) {
            configService.upsertConfig(ACTIVITY_TRACKING_NOTIFIED.configValue, "true", guildIdStr)
        }
    }

    @Test
    fun `notifyMembersOnFirstEnable DMing multiple human members opens a channel for each`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.id } returns guildIdStr
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Server"
        every {
            configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        } returns null

        val userA = mockk<User>(relaxed = true)
        every { userA.isBot } returns false
        every { userA.id } returns "10"
        val memberA = mockk<Member>(relaxed = true)
        every { memberA.user } returns userA

        val userB = mockk<User>(relaxed = true)
        every { userB.isBot } returns false
        every { userB.id } returns "11"
        val memberB = mockk<Member>(relaxed = true)
        every { memberB.user } returns userB

        every { guild.members } returns listOf(memberA, memberB)

        notifier.notifyMembersOnFirstEnable(guild)

        verify(exactly = 1) { userA.openPrivateChannel() }
        verify(exactly = 1) { userB.openPrivateChannel() }
        verify(exactly = 1) {
            configService.upsertConfig(ACTIVITY_TRACKING_NOTIFIED.configValue, "true", guildIdStr)
        }
    }
}
