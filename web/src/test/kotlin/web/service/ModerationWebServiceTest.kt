package web.service

import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.UserService
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
    private lateinit var initiativeResolver: InitiativeResolver
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
        initiativeResolver = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = ModerationWebService(jda, userService, configService, introWebService, initiativeResolver)

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
}
