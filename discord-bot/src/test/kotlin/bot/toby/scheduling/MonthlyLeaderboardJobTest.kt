package bot.toby.scheduling

import database.dto.ConfigDto
import database.dto.MonthlyCreditSnapshotDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.UserService
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MonthlyLeaderboardJobTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var configService: ConfigService
    private lateinit var guild: Guild
    private lateinit var channel: TextChannel
    private lateinit var job: MonthlyLeaderboardJob

    private val guildId = 100L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns mutableListOf(guild).iterator()
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
        every { guild.systemChannel } returns channel
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns true
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction

        job = MonthlyLeaderboardJob(jda, userService, voiceSessionService, snapshotService, configService)
    }

    private fun member(id: Long, name: String, isBot: Boolean = false): Member {
        val m = mockk<Member>(relaxed = true)
        val user = mockk<User>(relaxed = true)
        every { user.isBot } returns isBot
        every { m.user } returns user
        every { m.effectiveName } returns name
        every { guild.getMemberById(id) } returns m
        return m
    }

    @Test
    fun `postMonthlyLeaderboard skips guilds with no tracked users`() {
        every { userService.listGuildUsers(guildId) } returns emptyList()

        job.postMonthlyLeaderboard()

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { snapshotService.upsert(any()) }
    }

    @Test
    fun `postMonthlyLeaderboard writes snapshot for each user for the current month`() {
        val alice = UserDto(discordId = 1L, guildId = guildId)
            .apply { socialCredit = 500L; tobyCoins = 7L }
        val bob = UserDto(discordId = 2L, guildId = guildId)
            .apply { socialCredit = 300L; tobyCoins = 11L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice, bob)
        member(1L, "Alice")
        member(2L, "Bob")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null

        val snapshots = mutableListOf<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsert(capture(snapshots)) } answers { firstArg() }

        job.postMonthlyLeaderboard()

        assertEquals(2, snapshots.size)
        val byUser = snapshots.associateBy { it.discordId }
        assertEquals(500L, byUser[1L]?.socialCredit)
        assertEquals(300L, byUser[2L]?.socialCredit)
        // Regression guard: the scheduler must also snapshot the user's TOBY
        // balance, otherwise the wallet "+/- this month" delta permanently
        // reads as 0 (or current, depending on the fallback) after the next
        // month rolls over.
        assertEquals(7L, byUser[1L]?.tobyCoins)
        assertEquals(11L, byUser[2L]?.tobyCoins)
    }

    @Test
    fun `postMonthlyLeaderboard sends embed to leaderboard channel when configured`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()

        val configuredChannel = mockk<TextChannel>(relaxed = true)
        val configDto = ConfigDto(
            name = "LEADERBOARD_CHANNEL",
            value = "777",
            guildId = guildId.toString()
        )
        every { configService.getConfigByName(any(), guildId.toString()) } answers {
            val name = firstArg<String>()
            if (name == "LEADERBOARD_CHANNEL") configDto else null
        }
        every { guild.getTextChannelById(777L) } returns configuredChannel
        every { guild.selfMember.hasPermission(configuredChannel, *anyVararg<Permission>()) } returns true
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `postMonthlyLeaderboard falls back to system channel when configured channel absent`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null

        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `postMonthlyLeaderboard computes month delta using prior snapshot`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        // Prior snapshot says Alice had 200 at start of previous month → delta = 300.
        every { snapshotService.listForGuildDate(guildId, any()) } answers {
            val date = secondArg<java.time.LocalDate>()
            if (date == java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1))
                listOf(MonthlyCreditSnapshotDto(discordId = 1L, guildId = guildId, socialCredit = 200L))
            else emptyList()
        }
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns mapOf(1L to 3600L)
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction

        job.postMonthlyLeaderboard()

        // The embed was sent to the system channel
        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `postMonthlyLeaderboard excludes bots and users without positive activity`() {
        val tobyBot = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 0L }
        val fratLayton = UserDto(discordId = 2L, guildId = guildId).apply { socialCredit = 0L }
        val alice = UserDto(discordId = 3L, guildId = guildId).apply { socialCredit = 100L }
        every { userService.listGuildUsers(guildId) } returns listOf(tobyBot, fratLayton, alice)
        member(1L, "TobyBot", isBot = true)
        member(2L, "FratLayton")
        member(3L, "Alice")
        // FratLayton's prior snapshot was 1880 → delta = -1880, no voice → should be filtered out.
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = 2L, guildId = guildId, socialCredit = 1880L)
        )
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        val description = embedSlot.captured.description ?: ""
        assertFalse(description.contains("TobyBot"), "Bot users must not appear on the leaderboard")
        assertFalse(description.contains("FratLayton"), "Users with no positive activity must not appear")
        assertTrue(description.contains("Alice"), "Users with positive activity should still appear")
    }

    @Test
    fun `postMonthlyLeaderboard shows no-activity message when only bots and inactive users exist`() {
        val tobyBot = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 0L }
        val fratLayton = UserDto(discordId = 2L, guildId = guildId).apply { socialCredit = 0L }
        every { userService.listGuildUsers(guildId) } returns listOf(tobyBot, fratLayton)
        member(1L, "TobyBot", isBot = true)
        member(2L, "FratLayton")
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = 2L, guildId = guildId, socialCredit = 1880L)
        )
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        val description = embedSlot.captured.description ?: ""
        assertTrue(description.startsWith("No activity recorded"), "Expected no-activity message, got: $description")
    }

    @Test
    fun `postMonthlyLeaderboard still writes snapshot even if posting fails`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        // System channel has no perms → resolveChannel returns null → no post, but snapshot still written.
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns false

        val snapshots = mutableListOf<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsert(capture(snapshots)) } answers { firstArg() }

        job.postMonthlyLeaderboard()

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        assertEquals(1, snapshots.size)
    }
}
