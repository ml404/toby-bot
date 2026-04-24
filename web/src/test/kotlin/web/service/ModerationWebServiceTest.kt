package web.service

import database.dto.ConfigDto
import database.dto.MonthlyCreditSnapshotDto
import database.dto.TitleDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.UserService
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModerationWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var introWebService: IntroWebService
    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var titleService: TitleService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var service: ModerationWebService

    private lateinit var guild: Guild

    private val guildId = 222L
    private val ownerId = 100L
    private val superUserId = 101L
    private val plainUserId = 102L
    private val targetUserId = 103L

    private lateinit var eventPublisher: org.springframework.context.ApplicationEventPublisher

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = ModerationWebService(
            jda,
            userService,
            configService,
            introWebService,
            voiceSessionService,
            titleService,
            snapshotService,
            eventPublisher
        )

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun mockMember(id: Long, isOwner: Boolean = false): Member {
        val m = mockk<Member>(relaxed = true)
        every { m.idLong } returns id
        every { m.id } returns id.toString()
        every { m.isOwner } returns isOwner
        every { guild.getMemberById(id) } returns m
        return m
    }

    // ---- canModerate ----

    @Test
    fun `canModerate true for owner`() {
        mockMember(ownerId, isOwner = true)
        every { introWebService.isSuperUser(ownerId, guildId) } returns false
        assertTrue(service.canModerate(ownerId, guildId))
    }

    @Test
    fun `canModerate true for superuser`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        assertTrue(service.canModerate(superUserId, guildId))
    }

    @Test
    fun `canModerate false for plain user`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        assertFalse(service.canModerate(plainUserId, guildId))
    }

    @Test
    fun `canModerate false when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertFalse(service.canModerate(ownerId, guildId))
    }

    // ---- togglePermission ----

    @Test
    fun `togglePermission denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.togglePermission(plainUserId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertEquals("You are not allowed to moderate this server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `togglePermission denies self-change`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.togglePermission(superUserId, guildId, superUserId, UserDto.Permissions.MUSIC)
        assertEquals("You cannot change your own permissions.", err)
    }

    @Test
    fun `togglePermission SUPERUSER denied for non-owner`() {
        mockMember(superUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.togglePermission(superUserId, guildId, targetUserId, UserDto.Permissions.SUPERUSER)
        assertEquals("Only the server owner can toggle SUPERUSER.", err)
    }

    @Test
    fun `togglePermission flips MUSIC and persists`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { musicPermission = true }
        every { userService.getUserById(targetUserId, guildId) } returns target
        every { userService.getUserById(ownerId, guildId) } returns UserDto(discordId = ownerId, guildId = guildId)

        val err = service.togglePermission(ownerId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertNull(err)
        assertFalse(target.musicPermission)
        verify(exactly = 1) { userService.updateUser(target) }
    }

    @Test
    fun `togglePermission superuser cannot adjust another superuser`() {
        mockMember(superUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val actor = UserDto(discordId = superUserId, guildId = guildId).apply { superUser = true }
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { superUser = true }
        every { userService.getUserById(superUserId, guildId) } returns actor
        every { userService.getUserById(targetUserId, guildId) } returns target

        val err = service.togglePermission(superUserId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertNotNull(err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `togglePermission creates missing UserDto before flipping`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        every { userService.getUserById(targetUserId, guildId) } returns null
        every { userService.getUserById(ownerId, guildId) } returns UserDto(discordId = ownerId, guildId = guildId)

        val err = service.togglePermission(ownerId, guildId, targetUserId, UserDto.Permissions.DIG)
        assertNull(err)
        verify { userService.createNewUser(any()) }
        verify { userService.updateUser(any()) }
    }

    // ---- adjustSocialCredit ----

    @Test
    fun `adjustSocialCredit rejects non-owner`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.adjustSocialCredit(superUserId, guildId, targetUserId, 10)
        assertEquals("Only the server owner can adjust social credit.", err)
    }

    @Test
    fun `adjustSocialCredit increments and persists`() {
        mockMember(ownerId, isOwner = true)
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { socialCredit = 5L }
        every { userService.getUserById(targetUserId, guildId) } returns target

        val err = service.adjustSocialCredit(ownerId, guildId, targetUserId, 3)
        assertNull(err)
        assertEquals(8L, target.socialCredit)
    }

    // ---- updateConfig ----

    @Test
    fun `updateConfig rejects non-owner`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.updateConfig(superUserId, guildId, ConfigDto.Configurations.VOLUME, "50")
        assertEquals("Only the server owner can change guild config.", err)
    }

    @Test
    fun `updateConfig rejects non-integer volume`() {
        mockMember(ownerId, isOwner = true)
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.VOLUME, "not-a-number")
        assertEquals("Value must be a whole number.", err)
    }

    @Test
    fun `updateConfig creates new config when none exists`() {
        mockMember(ownerId, isOwner = true)
        every { configService.getConfigByName(ConfigDto.Configurations.VOLUME.configValue, guildId.toString()) } returns null

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.VOLUME, "75")
        assertNull(err)
        verify { configService.createNewConfig(match { it.value == "75" }) }
    }

    @Test
    fun `updateConfig rejects MOVE when channel name does not exist`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getVoiceChannelsByName("nope", true) } returns emptyList()

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.MOVE, "nope")
        assertNotNull(err)
        verify(exactly = 0) { configService.createNewConfig(any()) }
        verify(exactly = 0) { configService.updateConfig(any()) }
    }

    @Test
    fun `updateConfig accepts MOVE when channel name exists`() {
        mockMember(ownerId, isOwner = true)
        val vc = mockk<VoiceChannel>(relaxed = true)
        every { guild.getVoiceChannelsByName("afk", true) } returns listOf(vc)
        every { configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, guildId.toString()) } returns null

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.MOVE, "afk")
        assertNull(err)
        verify { configService.createNewConfig(match { it.value == "afk" }) }
    }

    // ---- kickMember ----

    @Test
    fun `kickMember denied for non-moderator`() {
        mockMember(plainUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.kickMember(plainUserId, guildId, targetUserId, null)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `kickMember denied when actor lacks KICK_MEMBERS`() {
        val actor = mockMember(ownerId, isOwner = true)
        val target = mockMember(targetUserId)
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { actor.canInteract(target) } returns true
        every { actor.hasPermission(Permission.KICK_MEMBERS) } returns false

        val err = service.kickMember(ownerId, guildId, targetUserId, null)
        assertNotNull(err)
    }

    // ---- moveMembers ----

    @Test
    fun `moveMembers error when destination not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getVoiceChannelById(999L) } returns null

        val result = service.moveMembers(ownerId, guildId, 999L, listOf(targetUserId))
        assertEquals("Target voice channel not found.", result.error)
    }

    @Test
    fun `moveMembers error when member list empty`() {
        mockMember(ownerId, isOwner = true)
        val result = service.moveMembers(ownerId, guildId, 999L, emptyList())
        assertEquals("Select at least one member to move.", result.error)
    }

    // ---- muteVoiceChannel ----

    @Test
    fun `muteVoiceChannel denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val result = service.muteVoiceChannel(plainUserId, guildId, 555L, true)
        assertEquals("You are not allowed to moderate this server.", result.error)
    }

    // ---- createPoll ----

    @Test
    fun `createPoll rejects fewer than 2 options`() {
        mockMember(ownerId, isOwner = true)
        val err = service.createPoll(ownerId, guildId, 333L, "Yes?", listOf("only one"))
        assertEquals("Provide at least 2 options.", err)
    }

    @Test
    fun `createPoll rejects more than 10 options`() {
        mockMember(ownerId, isOwner = true)
        val options = (1..11).map { "opt$it" }
        val err = service.createPoll(ownerId, guildId, 333L, "Q?", options)
        assertNotNull(err)
        assertTrue(err!!.contains("10"))
    }

    @Test
    fun `createPoll rejects empty question`() {
        mockMember(ownerId, isOwner = true)
        val err = service.createPoll(ownerId, guildId, 333L, "   ", listOf("a", "b"))
        assertEquals("Question is required.", err)
    }

    @Test
    fun `createPoll denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.createPoll(plainUserId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `createPoll error when text channel not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns null

        val err = service.createPoll(ownerId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("Text channel not found.", err)
    }

    @Test
    fun `createPoll error when bot lacks channel permission`() {
        mockMember(ownerId, isOwner = true)
        val channel = mockk<TextChannel>(relaxed = true)
        every { guild.getTextChannelById(333L) } returns channel
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { bot.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION) } returns false

        val err = service.createPoll(ownerId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("Bot cannot post in that channel.", err)
    }

    // ---- getModeratableGuilds ----

    @Test
    fun `getModeratableGuilds filters to guilds where actor can moderate`() {
        val mutual = listOf(
            GuildInfo(id = guildId.toString(), name = "A", iconHash = null),
            GuildInfo(id = "999", name = "B", iconHash = null)
        )
        every { introWebService.getMutualGuilds("token") } returns mutual
        mockMember(ownerId, isOwner = true)
        every { jda.getGuildById(999L) } returns null

        val result = service.getModeratableGuilds("token", ownerId)
        assertEquals(1, result.size)
        assertEquals(guildId.toString(), result.first().id)
    }

    // ---- getLeaderboard ----

    @Test
    fun `getLeaderboard returns rows sorted by socialCredit with title and voice stats`() {
        val topUser = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 500L
            activeTitleId = 7L
        }
        val midUser = UserDto(discordId = targetUserId, guildId = guildId).apply {
            socialCredit = 200L
            activeTitleId = null
        }
        every { userService.listGuildUsers(guildId) } returns listOf(topUser, midUser)
        mockMember(plainUserId)
        mockMember(targetUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns mapOf(
            plainUserId to 7200L,
            targetUserId to 120L
        )
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns mapOf(
            plainUserId to 3600L
        )
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 300L),
            MonthlyCreditSnapshotDto(discordId = targetUserId, guildId = guildId, socialCredit = 200L)
        )
        every { titleService.getById(7L) } returns TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)

        val rows = service.getLeaderboard(guildId)

        assertEquals(2, rows.size)
        assertEquals(1, rows[0].rank)
        assertEquals(plainUserId.toString(), rows[0].discordId)
        assertEquals(500L, rows[0].socialCredit)
        assertEquals("⭐ Comrade", rows[0].title)
        assertEquals(7200L, rows[0].voiceSecondsLifetime)
        assertEquals(3600L, rows[0].voiceSecondsThisMonth)
        assertEquals(200L, rows[0].creditsEarnedThisMonth)
        assertEquals(2, rows[1].rank)
        assertNull(rows[1].title)
        assertEquals(0L, rows[1].creditsEarnedThisMonth)
        assertEquals("2h 0m", rows[0].voiceLifetimeDisplay)
        assertEquals("1h 0m", rows[0].voiceThisMonthDisplay)
    }

    @Test
    fun `getLeaderboard returns empty list when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val rows = service.getLeaderboard(guildId)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `getLeaderboard treats users with no prior snapshot as zero delta`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 1_000L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        // Lazy-write returns a baseline DTO whose socialCredit matches current,
        // so the delta for a first-time visit is 0 (consistent with the old
        // behaviour — earnings from 1st-of-month to this visit get absorbed).
        every { snapshotService.upsertIfMissing(any()) } answers { firstArg() }

        val rows = service.getLeaderboard(guildId)
        assertEquals(0L, rows.first().creditsEarnedThisMonth)
    }

    @Test
    fun `getLeaderboard lazy-writes a baseline when none exists for this month`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 1_000L
            tobyCoins = 12L
        }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        val captured = slot<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsertIfMissing(capture(captured)) } answers { firstArg() }

        service.getLeaderboard(guildId)

        // Baseline must capture BOTH counters so the wallet leaderboard is
        // consistent with the social-credit leaderboard. Writing only
        // socialCredit here and leaving tobyCoins = 0 would regress the
        // wallet delta to "all current coins earned this month".
        assertEquals(plainUserId, captured.captured.discordId)
        assertEquals(guildId, captured.captured.guildId)
        assertEquals(1_000L, captured.captured.socialCredit)
        assertEquals(12L, captured.captured.tobyCoins)
    }

    @Test
    fun `getLeaderboard does NOT lazy-write when baseline already exists`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 1_050L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 1_000L)
        )

        val rows = service.getLeaderboard(guildId)

        // Skipping lazy-write is the whole point — scheduled job already
        // wrote the baseline, the user earned 50 since then.
        assertEquals(50L, rows.first().creditsEarnedThisMonth)
        verify(exactly = 0) { snapshotService.upsertIfMissing(any()) }
    }

    @Test
    fun `getLeaderboard tolerates missing voice tables by returning zeroed voice stats`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } throws
            RuntimeException("relation \"voice_session\" does not exist")
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } throws
            RuntimeException("relation \"voice_session\" does not exist")
        every { snapshotService.listForGuildDate(guildId, any()) } throws
            RuntimeException("relation \"monthly_credit_snapshot\" does not exist")
        // Lazy-write would hit the same missing table; simulate that so the
        // assertion reflects real-world behaviour.
        every { snapshotService.upsertIfMissing(any()) } throws
            RuntimeException("relation \"monthly_credit_snapshot\" does not exist")

        val rows = service.getLeaderboard(guildId)

        assertEquals(1, rows.size)
        assertEquals(500L, rows[0].socialCredit)
        assertEquals(0L, rows[0].voiceSecondsLifetime)
        assertEquals(0L, rows[0].voiceSecondsThisMonth)
        assertEquals(0L, rows[0].creditsEarnedThisMonth)
    }

    @Test
    fun `getLeaderboard tolerates missing title rows without breaking the whole page`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 100L
            activeTitleId = 42L
        }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { titleService.getById(42L) } throws
            RuntimeException("relation \"title\" does not exist")

        val rows = service.getLeaderboard(guildId)

        assertEquals(1, rows.size)
        assertNull(rows[0].title)
    }


    // ---- updateConfig LEADERBOARD_CHANNEL ----

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL rejects non-numeric id`() {
        mockMember(ownerId, isOwner = true)
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "not-a-number")
        assertEquals("Channel id must be numeric.", err)
    }

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL rejects unknown channel id`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(111L) } returns null
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "111")
        assertEquals("No text channel with that id exists in this server.", err)
    }

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL persists valid channel id`() {
        mockMember(ownerId, isOwner = true)
        val textChannel = mockk<TextChannel>(relaxed = true)
        every { guild.getTextChannelById(111L) } returns textChannel
        every { configService.getConfigByName(any(), any()) } returns null

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "111")
        assertNull(err)
        verify(exactly = 1) { configService.createNewConfig(any()) }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING=true publishes the first-enable event`() {
        mockMember(ownerId, isOwner = true)
        every { configService.getConfigByName(any(), any()) } returns null

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "true")

        assertNull(err)
        // Event must fire regardless of previous value — the listener
        // de-duplicates via ACTIVITY_TRACKING_NOTIFIED, so publishing on
        // every "true" write is the right contract.
        verify(exactly = 1) {
            eventPublisher.publishEvent(common.events.ActivityTrackingEnabled(guildId))
        }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING=false does NOT publish the event`() {
        mockMember(ownerId, isOwner = true)
        every { configService.getConfigByName(any(), any()) } returns null

        service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "false")

        // Disabling tracking must never trigger member DMs.
        verify(exactly = 0) {
            eventPublisher.publishEvent(any<common.events.ActivityTrackingEnabled>())
        }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING rejects non-boolean values`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "maybe")

        assertEquals("Value must be true or false.", err)
        verify(exactly = 0) { configService.createNewConfig(any()) }
        verify(exactly = 0) { configService.updateConfig(any()) }
        verify(exactly = 0) {
            eventPublisher.publishEvent(any<common.events.ActivityTrackingEnabled>())
        }
    }

    @Test
    fun `getLeaderboard queries voice for the CURRENT month, not the previous one`() {
        // Regression: the old code passed [prevMonthStart, thisMonthStart),
        // which is the just-finished month — copy-paste from
        // MonthlyLeaderboardJob (which runs on the 1st and reports the
        // previous month). The live web view should query [thisMonthStart,
        // nextMonthStart) so today's sessions actually count.
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 100L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()

        val fromCaptor = slot<java.time.Instant>()
        val untilCaptor = slot<java.time.Instant>()
        every {
            voiceSessionService.sumCountedSecondsInRangeByUser(
                guildId,
                capture(fromCaptor),
                capture(untilCaptor)
            )
        } returns emptyMap()

        service.getLeaderboard(guildId)

        val now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val expectedFrom = now.withDayOfMonth(1)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val expectedUntil = now.withDayOfMonth(1).plusMonths(1)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        assertEquals(expectedFrom, fromCaptor.captured,
            "voice range must START at this month's 1st, not last month's 1st")
        assertEquals(expectedUntil, untilCaptor.captured,
            "voice range must END at next month's 1st, not this month's 1st")
    }
}
