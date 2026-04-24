package web.service

import database.dto.ConfigDto
import database.dto.MonthlyCreditSnapshotDto
import database.dto.TitleDto
import database.dto.UserDto
import database.dto.UserOwnedTitleDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.UserService
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var titleRoleService: TitleRoleService
    private lateinit var service: ModerationWebService

    private lateinit var guild: Guild

    private val guildId = 222L
    private val ownerId = 100L
    private val superUserId = 101L
    private val plainUserId = 102L
    private val targetUserId = 103L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        titleRoleService = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = ModerationWebService(
            jda,
            userService,
            configService,
            introWebService,
            voiceSessionService,
            titleService,
            snapshotService,
            titleRoleService
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

        val rows = service.getLeaderboard(guildId)
        assertEquals(0L, rows.first().creditsEarnedThisMonth)
    }

    // ---- getTitlesForGuild ----

    @Test
    fun `getTitlesForGuild returns catalog with ownership, equipped flag, and balance`() {
        val titles = listOf(
            TitleDto(id = 1L, label = "A", cost = 100L, description = "d1"),
            TitleDto(id = 2L, label = "B", cost = 500L, description = null, colorHex = "#FFD700", hoisted = true)
        )
        val owned = listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 350L
            activeTitleId = 1L
        }
        every { titleService.listAll() } returns titles
        every { titleService.listOwned(plainUserId) } returns owned
        every { userService.getUserById(plainUserId, guildId) } returns actor

        val view = service.getTitlesForGuild(guildId, plainUserId)
        assertEquals(2, view.catalog.size)
        assertEquals(setOf(1L), view.ownedTitleIds)
        assertEquals(1L, view.equippedTitleId)
        assertEquals(350L, view.balance)
    }

    // ---- buyTitle ----

    @Test
    fun `buyTitle deducts credits and records purchase`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertNull(err)
        assertEquals(400L, actor.socialCredit)
        verify(exactly = 1) { userService.updateUser(actor) }
        verify(exactly = 1) { titleService.recordPurchase(plainUserId, 1L) }
    }

    @Test
    fun `buyTitle rejects when user already owns the title`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns true

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("You already own this title.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `buyTitle rejects when credits insufficient`() {
        val title = TitleDto(id = 1L, label = "A", cost = 1_000L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 50L }
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertNotNull(err)
        assertTrue(err!!.contains("Not enough credits"))
        assertEquals(50L, actor.socialCredit)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `buyTitle rejects when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("Bot is not in that server.", err)
    }

    @Test
    fun `buyTitle rejects when user has no profile`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        every { titleService.getById(1L) } returns title
        every { userService.getUserById(plainUserId, guildId) } returns null

        val err = service.buyTitle(plainUserId, guildId, 1L)
        assertEquals("You don't have a profile in that server yet.", err)
    }

    @Test
    fun `buyTitle rejects when title does not exist`() {
        every { titleService.getById(99L) } returns null
        val err = service.buyTitle(plainUserId, guildId, 99L)
        assertEquals("Title not found.", err)
    }

    // ---- equipTitle ----

    @Test
    fun `equipTitle sets activeTitleId when role service succeeds`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns true
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.equip(guild, member, title, setOf(1L)) } returns TitleRoleResult.Ok

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertNull(err)
        assertEquals(1L, actor.activeTitleId)
        verify(exactly = 1) { userService.updateUser(actor) }
    }

    @Test
    fun `equipTitle rejects when user does not own the title`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns false

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertEquals("You don't own this title.", err)
        assertNull(actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `equipTitle surfaces role service error and does not persist`() {
        val title = TitleDto(id = 1L, label = "A", cost = 100L)
        val actor = UserDto(discordId = plainUserId, guildId = guildId)
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.getById(1L) } returns title
        every { titleService.owns(plainUserId, 1L) } returns true
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.equip(guild, member, title, any()) } returns TitleRoleResult.Error("no Manage Roles")

        val err = service.equipTitle(plainUserId, guildId, 1L)
        assertEquals("no Manage Roles", err)
        assertNull(actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- unequipTitle ----

    @Test
    fun `unequipTitle clears activeTitleId when role service succeeds`() {
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { activeTitleId = 1L }
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.listOwned(plainUserId) } returns listOf(UserOwnedTitleDto(discordId = plainUserId, titleId = 1L))
        every { titleRoleService.unequip(guild, member, setOf(1L)) } returns TitleRoleResult.Ok

        val err = service.unequipTitle(plainUserId, guildId)
        assertNull(err)
        assertNull(actor.activeTitleId)
        verify(exactly = 1) { userService.updateUser(actor) }
    }

    @Test
    fun `unequipTitle surfaces role service error`() {
        val actor = UserDto(discordId = plainUserId, guildId = guildId).apply { activeTitleId = 1L }
        val member = mockMember(plainUserId)
        every { userService.getUserById(plainUserId, guildId) } returns actor
        every { titleService.listOwned(plainUserId) } returns emptyList()
        every { titleRoleService.unequip(guild, member, any()) } returns TitleRoleResult.Error("bot has no Manage Roles")

        val err = service.unequipTitle(plainUserId, guildId)
        assertEquals("bot has no Manage Roles", err)
        assertEquals(1L, actor.activeTitleId)
        verify(exactly = 0) { userService.updateUser(any()) }
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
}
