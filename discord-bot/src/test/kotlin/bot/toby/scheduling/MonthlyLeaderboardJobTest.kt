package bot.toby.scheduling

import database.dto.guild.ConfigDto
import database.dto.economy.MonthlyCoinHoldingSnapshotDto
import database.dto.economy.MonthlyCreditSnapshotDto
import database.dto.economy.UserCoinHoldingDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.economy.MonthlyCoinHoldingSnapshotService
import database.service.economy.MonthlyCreditSnapshotService
import database.service.economy.UserCoinHoldingService
import database.service.activity.UbiDailyService
import database.service.user.UserService
import database.service.activity.VoiceSessionService
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class MonthlyLeaderboardJobTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var configService: ConfigService
    private lateinit var ubiDailyService: UbiDailyService
    private lateinit var coinHoldingService: UserCoinHoldingService
    private lateinit var coinHoldingSnapshotService: MonthlyCoinHoldingSnapshotService
    private lateinit var hourGate: GuildHourGate
    private lateinit var guild: Guild
    private lateinit var channel: TextChannel
    private lateinit var job: MonthlyLeaderboardJob

    private val guildId = 100L

    // Keep the real calendar date (so the existing LocalDate.now(UTC)-based
    // stubs stay correct) but fix the hour to 12:00 UTC — the default
    // MONTHLY_LEADERBOARD_HOUR — so the per-guild hour gate fires.
    private val clock: Clock = Clock.fixed(
        LocalDate.now(ZoneOffset.UTC).atTime(12, 0).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC,
    )

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        ubiDailyService = mockk(relaxed = true)
        coinHoldingService = mockk(relaxed = true)
        coinHoldingSnapshotService = mockk(relaxed = true)
        // Relaxed configService → getConfigByName null → gate uses default hour 12.
        hourGate = GuildHourGate(configService)
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
        // Default: the boundary freeze echoes back the dto it was given, so the
        // frozen end-of-month total equals the user's balance unless a test
        // overrides upsertIfMissing to simulate a pre-existing boundary row.
        every { snapshotService.upsertIfMissing(any()) } answers { firstArg() }

        job = MonthlyLeaderboardJob(
            jda, userService, voiceSessionService, snapshotService, configService,
            ubiDailyService, hourGate, coinHoldingService, coinHoldingSnapshotService, clock,
        )
    }

    /** Build a job whose clock is fixed at [hour] UTC on the current date. */
    private fun jobAtHour(hour: Int): MonthlyLeaderboardJob {
        val c = Clock.fixed(
            LocalDate.now(ZoneOffset.UTC).atTime(hour, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC,
        )
        return MonthlyLeaderboardJob(
            jda, userService, voiceSessionService, snapshotService, configService,
            ubiDailyService, hourGate, coinHoldingService, coinHoldingSnapshotService, c,
        )
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
    fun `postMonthlyLeaderboard skips a guild when the current UTC hour is not its hour`() {
        // Default leaderboard hour is 12; running the hourly tick at 09:00 UTC
        // should gate the guild out before any user/snapshot lookup.
        jobAtHour(9).postMonthlyLeaderboard()

        verify(exactly = 0) { userService.listGuildUsers(any()) }
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { snapshotService.upsertIfMissing(any()) }
    }

    @Test
    fun `postMonthlyLeaderboard skips guilds with no tracked users`() {
        every { userService.listGuildUsers(guildId) } returns emptyList()

        job.postMonthlyLeaderboard()

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { snapshotService.upsertIfMissing(any()) }
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
        every { snapshotService.upsertIfMissing(capture(snapshots)) } answers { firstArg() }

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
    fun `postMonthlyLeaderboard freezes each non-TOBY coin holding for the current month`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L; tobyCoins = 7L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        // Alice holds two non-TOBY coins at the boundary.
        every { coinHoldingService.listForGuild(guildId) } returns listOf(
            UserCoinHoldingDto(discordId = 1L, guildId = guildId, coin = "MOON", amount = 20L),
            UserCoinHoldingDto(discordId = 1L, guildId = guildId, coin = "RUFF", amount = 5L),
        )
        val frozen = mutableListOf<MonthlyCoinHoldingSnapshotDto>()
        every { coinHoldingSnapshotService.upsertIfMissing(capture(frozen)) } answers { firstArg() }

        job.postMonthlyLeaderboard()

        val byCoin = frozen.associateBy { it.coin }
        assertEquals(20L, byCoin["MOON"]?.amount, "MOON balance must be frozen at the boundary")
        assertEquals(5L, byCoin["RUFF"]?.amount, "RUFF balance must be frozen at the boundary")
        assertEquals(1L, byCoin["MOON"]?.discordId)
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
        // Alice started the month at 0 → delta = 100 → positive activity, should appear.
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = 2L, guildId = guildId, socialCredit = 1880L),
            MonthlyCreditSnapshotDto(discordId = 3L, guildId = guildId, socialCredit = 0L)
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
    fun `postMonthlyLeaderboard does not count full balance as earnings when no prior snapshot exists`() {
        // Regression: with no prior-month snapshot, the board must NOT treat a
        // user's entire current balance as "earned last month" — doing so ranks
        // everyone by their lifetime total and turns the board into a current-
        // standings list instead of a last-month snapshot. A missing baseline
        // means 0 last-month credits (matches the web leaderboards), so a user
        // with credits but no baseline and no voice is filtered out entirely.
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 5000L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        val description = embedSlot.captured.description ?: ""
        assertTrue(
            description.startsWith("No activity recorded"),
            "Expected no-activity message (baseline absent → 0 delta), got: $description"
        )
        assertFalse(
            description.contains("5000"),
            "Lifetime balance must not be reported as last-month earnings, got: $description"
        )
    }

    @Test
    fun `postMonthlyLeaderboard subtracts UBI grants from the credits delta`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 1000L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        // Prior snapshot: Alice had 0 at start of previous month. Raw delta = 1000.
        every { snapshotService.listForGuildDate(guildId, any()) } answers {
            val date = secondArg<java.time.LocalDate>()
            if (date == java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1))
                listOf(MonthlyCreditSnapshotDto(discordId = 1L, guildId = guildId, socialCredit = 0L))
            else emptyList()
        }
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        // 750 of those 1000 credits came from UBI.
        every { ubiDailyService.sumGrantedInRangeByUser(guildId, any(), any()) } returns mapOf(1L to 750L)

        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        // 1000 raw - 750 UBI = 250 effective earnings.
        val description = embedSlot.captured.description ?: ""
        assert(description.contains("250 credits")) {
            "expected description to show 250 credits after UBI subtraction, got: $description"
        }
    }

    @Test
    fun `postMonthlyLeaderboard still writes snapshot even if posting fails`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        // No channel exists at all → nothing to post to, but snapshot still written.
        every { guild.systemChannel } returns null

        val snapshots = mutableListOf<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsertIfMissing(capture(snapshots)) } answers { firstArg() }

        job.postMonthlyLeaderboard()

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        assertEquals(1, snapshots.size)
    }

    @Test
    fun `system channel failing the permission check is still attempted as the last resort`() {
        // Same contract as NotificationRouter: a computed-permission "no"
        // must degrade to an attempted send (failure logged by JDA's
        // callback), never a silent drop, while a channel exists.
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns false

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `unwritable configured channel is preferred over the system channel in the best-effort attempt`() {
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()

        val configuredChannel = mockk<TextChannel>(relaxed = true)
        val configDto = ConfigDto(name = "LEADERBOARD_CHANNEL", value = "777", guildId = guildId.toString())
        every { configService.getConfigByName(any(), guildId.toString()) } answers {
            if (firstArg<String>() == "LEADERBOARD_CHANNEL") configDto else null
        }
        every { guild.getTextChannelById(777L) } returns configuredChannel
        // Nothing passes the perm check; the attempt goes to the
        // admin-configured channel, not the system channel.
        every { guild.selfMember.hasPermission(any<TextChannel>(), *anyVararg<Permission>()) } returns false

        job.postMonthlyLeaderboard()

        verify(exactly = 1) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---------------------------------------------------------------------
    // The board must post a SNAPSHOT of last month, frozen at the midnight
    // month boundary — not a figure derived from the live balance at the
    // (later) posting hour. The three tests below pin that contract.
    // ---------------------------------------------------------------------

    @Test
    fun `earnings come from the frozen month-boundary snapshot, not the live balance`() {
        val today = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val prevMonth = today.minusMonths(1)
        // Alice's LIVE balance has already drifted to 999 in the new month, but
        // the authoritative end-of-last-month boundary snapshot froze her at 400.
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 999L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, prevMonth) } returns
            listOf(MonthlyCreditSnapshotDto(discordId = 1L, guildId = guildId, snapshotDate = prevMonth, socialCredit = 100L))
        // The boundary snapshot already exists (frozen at midnight), so the
        // freeze is a no-op that returns the authoritative 400 — never 999.
        every { snapshotService.upsertIfMissing(any()) } answers {
            val dto = firstArg<MonthlyCreditSnapshotDto>()
            if (dto.snapshotDate == today)
                MonthlyCreditSnapshotDto(discordId = dto.discordId, guildId = guildId, snapshotDate = today, socialCredit = 400L)
            else dto
        }
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        val description = embedSlot.captured.description ?: ""
        // Earnings = 400 (frozen end) - 100 (frozen start) = 300, NOT 999 - 100 = 899.
        assertTrue(description.contains("300 credits"),
            "expected frozen-boundary earnings of 300, got: $description")
        assertFalse(description.contains("899"),
            "must not derive earnings from the live balance (899): $description")
    }

    @Test
    fun `end-of-month total comes from the frozen boundary even after the balance drops`() {
        val today = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val prevMonth = today.minusMonths(1)
        // Alice ended last month at 400 (frozen) but has since spent down to 50
        // in the new month. The board is a snapshot of last month, so it must
        // still show her 400 total and her 300 of earnings.
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 50L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, prevMonth) } returns
            listOf(MonthlyCreditSnapshotDto(discordId = 1L, guildId = guildId, snapshotDate = prevMonth, socialCredit = 100L))
        every { snapshotService.upsertIfMissing(any()) } answers {
            val dto = firstArg<MonthlyCreditSnapshotDto>()
            if (dto.snapshotDate == today)
                MonthlyCreditSnapshotDto(discordId = dto.discordId, guildId = guildId, snapshotDate = today, socialCredit = 400L)
            else dto
        }
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val embedSlot = slot<MessageEmbed>()
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(capture(embedSlot)) } returns createAction

        job.postMonthlyLeaderboard()

        val description = embedSlot.captured.description ?: ""
        assertFalse(description.startsWith("No activity recorded"),
            "live balance (50 < 100 baseline) wrongly drops Alice from the board: $description")
        assertTrue(description.contains("400 total"),
            "expected end-of-month total of 400 (frozen), got: $description")
        assertTrue(description.contains("300 credits"),
            "expected frozen-boundary earnings of 300, got: $description")
    }

    @Test
    fun `boundary snapshot is frozen at the midnight tick even when the guild posts later`() {
        // A guild posts at the default hour (12:00). At the 00:00 tick the job
        // must still freeze the authoritative month-boundary snapshot for that
        // guild (so the noon post reflects midnight) without posting yet.
        val alice = UserDto(discordId = 1L, guildId = guildId).apply { socialCredit = 250L }
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        member(1L, "Alice")
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
        val today = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)

        jobAtHour(0).postMonthlyLeaderboard()

        // Frozen the boundary for the user at midnight…
        verify(atLeast = 1) {
            snapshotService.upsertIfMissing(match { it.discordId == 1L && it.snapshotDate == today })
        }
        // …but did NOT post (the guild's posting hour is 12:00, not 00:00).
        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }
}
