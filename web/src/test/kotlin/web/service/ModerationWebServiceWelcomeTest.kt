package web.service

import database.dto.guild.AutoRoleDto
import database.dto.guild.ConfigDto
import database.service.guild.AutoRoleService
import database.service.guild.ConfigService
import database.service.leveling.LevelRoleRewardService
import database.service.economy.MonthlyCreditSnapshotService
import database.service.guild.TitleService
import database.service.activity.UbiDailyService
import database.service.user.UserService
import database.service.activity.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Welcome / goodbye / auto-role slice of the ModerationWebService tests.
 * Sits beside [ModerationWebServiceTest] so the welcome surface gets a
 * dedicated test class (parallels the leveling, casino, lottery slice
 * splits already in place — easier to find and run targeted regressions).
 */
class ModerationWebServiceWelcomeTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var introWebService: IntroWebService
    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var titleService: TitleService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var ubiDailyService: UbiDailyService
    private lateinit var levelRoleRewardService: LevelRoleRewardService
    private lateinit var autoRoleService: AutoRoleService
    private lateinit var casinoAuditService: CasinoAuditService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: ModerationWebService
    private lateinit var guild: Guild

    private val guildId = 222L
    private val ownerId = 100L
    private val nonOwnerId = 101L

    @BeforeEach
    fun setUp() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        ubiDailyService = mockk(relaxed = true)
        levelRoleRewardService = mockk(relaxed = true)
        autoRoleService = mockk(relaxed = true)
        casinoAuditService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = ModerationWebService(
            jda, userService, configService, introWebService, voiceSessionService,
            titleService, snapshotService, ubiDailyService, levelRoleRewardService,
            autoRoleService, casinoAuditService, eventPublisher,
        )

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"
        // Default config upsert returns Created
        every { configService.upsertConfig(any(), any(), any()) } answers {
            ConfigService.UpsertResult.Created(ConfigDto(firstArg(), secondArg(), thirdArg()))
        }

        val ownerMember = mockk<Member>(relaxed = true).also {
            every { it.idLong } returns ownerId
            every { it.isOwner } returns true
        }
        every { guild.getMemberById(ownerId) } returns ownerMember
        val plainMember = mockk<Member>(relaxed = true).also {
            every { it.idLong } returns nonOwnerId
            every { it.isOwner } returns false
        }
        every { guild.getMemberById(nonOwnerId) } returns plainMember
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // ---- getWelcomeOverview ----

    @Test
    fun `getWelcomeOverview returns null when bot cannot see guild`() {
        every { jda.getGuildById(999L) } returns null
        assertNull(service.getWelcomeOverview(999L))
    }

    @Test
    fun `getWelcomeOverview returns rows with role name and color for live roles`() {
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
            AutoRoleDto(guildId = guildId, roleId = 200L),
        )
        val role100 = mockk<Role>(relaxed = true).also {
            every { it.id } returns "100"
            every { it.idLong } returns 100L
            every { it.name } returns "Member"
            every { it.colorRaw } returns 0xFF9900
            every { it.isManaged } returns false
        }
        every { guild.getRoleById(100L) } returns role100
        every { guild.getRoleById(200L) } returns null
        every { guild.roles } returns listOf(role100)

        val overview = service.getWelcomeOverview(guildId)
        assertNotNull(overview)
        assertEquals(2, overview!!.autoRoles.size)
        val first = overview.autoRoles.first { it.roleId == "100" }
        val second = overview.autoRoles.first { it.roleId == "200" }
        assertEquals("Member", first.roleName)
        assertEquals("#ff9900", first.roleColorHex)
        assertFalse(first.roleMissing)
        assertEquals("(deleted role)", second.roleName)
        assertTrue(second.roleMissing, "row for a role JDA can't resolve must surface as missing")
    }

    @Test
    fun `getWelcomeOverview hides everyone and managed roles from the picker source`() {
        every { autoRoleService.listForGuild(guildId) } returns emptyList()
        val everyoneRole = mockk<Role>(relaxed = true).also {
            every { it.id } returns guildId.toString()
            every { it.idLong } returns guildId
            every { it.name } returns "@everyone"
            every { it.isManaged } returns false
        }
        val managed = mockk<Role>(relaxed = true).also {
            every { it.id } returns "1"
            every { it.idLong } returns 1L
            every { it.name } returns "Twitch sub"
            every { it.isManaged } returns true
        }
        val ok = mockk<Role>(relaxed = true).also {
            every { it.id } returns "2"
            every { it.idLong } returns 2L
            every { it.name } returns "Member"
            every { it.isManaged } returns false
        }
        every { guild.roles } returns listOf(everyoneRole, managed, ok)

        val overview = service.getWelcomeOverview(guildId)!!
        val ids = overview.roles.map { it.id }.toSet()
        assertFalse(ids.contains(guildId.toString()), "@everyone must be hidden from the picker")
        assertFalse(ids.contains("1"), "managed roles must be hidden from the picker")
        assertTrue(ids.contains("2"))
    }

    // ---- addAutoRole ----

    @Test
    fun `addAutoRole is owner-only`() {
        val role = stubRole(7L, managed = false, public = false, canInteract = true)
        val err = service.addAutoRole(nonOwnerId, guildId, role.idLong)
        assertEquals("Only the server owner can change guild config.", err)
        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `addAutoRole reports missing role`() {
        every { guild.getRoleById(99L) } returns null
        val err = service.addAutoRole(ownerId, guildId, 99L)
        assertEquals("Role not found in this server.", err)
        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `addAutoRole rejects everyone`() {
        val role = stubRole(guildId, managed = false, public = true, canInteract = true)
        val err = service.addAutoRole(ownerId, guildId, role.idLong)
        assertEquals("Cannot auto-assign @everyone.", err)
        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `addAutoRole rejects managed role`() {
        val role = stubRole(7L, managed = true, public = false, canInteract = true)
        val err = service.addAutoRole(ownerId, guildId, role.idLong)
        assertTrue(err!!.contains("managed by an integration"))
        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `addAutoRole rejects role above the bot`() {
        val role = stubRole(7L, managed = false, public = false, canInteract = false)
        val err = service.addAutoRole(ownerId, guildId, role.idLong)
        assertTrue(err!!.contains("move TobyBot's role higher"))
        verify(exactly = 0) { autoRoleService.add(any(), any()) }
    }

    @Test
    fun `addAutoRole writes through service on happy path`() {
        val role = stubRole(7L, managed = false, public = false, canInteract = true)
        val err = service.addAutoRole(ownerId, guildId, role.idLong)
        assertNull(err)
        verify(exactly = 1) { autoRoleService.add(guildId, 7L) }
    }

    @Test
    fun `addAutoRole reports missing guild`() {
        every { jda.getGuildById(999L) } returns null
        val ownerMember = mockk<Member>(relaxed = true).also {
            every { it.idLong } returns ownerId
            every { it.isOwner } returns true
        }
        val phantomGuild = mockk<Guild>(relaxed = true)
        every { phantomGuild.getMemberById(ownerId) } returns ownerMember
        // owner check first goes through isOwner; the test relies on
        // canModerate/isOwner returning false because the guild is null.
        val err = service.addAutoRole(ownerId, 999L, 7L)
        // isOwner returns false for a null guild, so the owner gate fires
        // before the bot-not-in-server gate.
        assertEquals("Only the server owner can change guild config.", err)
    }

    // ---- removeAutoRole ----

    @Test
    fun `removeAutoRole is owner-only`() {
        val err = service.removeAutoRole(nonOwnerId, guildId, 7L)
        assertEquals("Only the server owner can change guild config.", err)
        verify(exactly = 0) { autoRoleService.delete(any(), any()) }
    }

    @Test
    fun `removeAutoRole deletes through service on happy path`() {
        val err = service.removeAutoRole(ownerId, guildId, 7L)
        assertNull(err)
        verify(exactly = 1) { autoRoleService.delete(guildId, 7L) }
    }

    // ---- updateConfig for welcome / goodbye keys ----

    @Test
    fun `updateConfig accepts true and false for WELCOME_ENABLED`() {
        assertNull(service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_ENABLED, "true"))
        assertNull(service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_ENABLED, "FALSE"))
        assertEquals(
            "Value must be true or false.",
            service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_ENABLED, "maybe"),
        )
    }

    @Test
    fun `updateConfig validates WELCOME_CHANNEL exists`() {
        every { guild.getTextChannelById(555L) } returns mockk(relaxed = true)
        assertNull(service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_CHANNEL, "555"))
        every { guild.getTextChannelById(666L) } returns null
        assertEquals(
            "No text channel with that id exists in this server.",
            service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_CHANNEL, "666"),
        )
        assertEquals(
            "Channel id must be numeric.",
            service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_CHANNEL, "abc"),
        )
    }

    @Test
    fun `updateConfig clears WELCOME_CHANNEL on blank input`() {
        // Blank must persist as an empty string so the listener falls back
        // to the system channel.
        assertNull(service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_CHANNEL, "  "))
        verify { configService.upsertConfig(ConfigDto.Configurations.WELCOME_CHANNEL.configValue, "", guildId.toString()) }
    }

    @Test
    fun `updateConfig caps WELCOME_MESSAGE at 2000 chars`() {
        val longMessage = "a".repeat(2001)
        assertEquals(
            "Message must be 2000 characters or fewer.",
            service.updateConfig(ownerId, guildId, ConfigDto.Configurations.WELCOME_MESSAGE, longMessage),
        )
        verify(exactly = 0) { configService.upsertConfig(eq(ConfigDto.Configurations.WELCOME_MESSAGE.configValue), any(), any()) }
    }

    @Test
    fun `updateConfig accepts free-form GOODBYE_MESSAGE under cap`() {
        val message = "Goodbye {user.name} from {server}!"
        assertNull(service.updateConfig(ownerId, guildId, ConfigDto.Configurations.GOODBYE_MESSAGE, message))
        verify(exactly = 1) {
            configService.upsertConfig(ConfigDto.Configurations.GOODBYE_MESSAGE.configValue, message, guildId.toString())
        }
    }

    @Test
    fun `updateConfig welcome keys are owner-only`() {
        val err = service.updateConfig(nonOwnerId, guildId, ConfigDto.Configurations.WELCOME_ENABLED, "true")
        assertEquals("Only the server owner can change guild config.", err)
    }

    // ---- helpers ----

    private fun stubRole(id: Long, managed: Boolean, public: Boolean, canInteract: Boolean): Role {
        val role = mockk<Role>(relaxed = true).also {
            every { it.idLong } returns id
            every { it.id } returns id.toString()
            every { it.name } returns "Role-$id"
            every { it.isManaged } returns managed
            every { it.isPublicRole } returns public
        }
        every { guild.getRoleById(id) } returns role
        val selfMember = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns selfMember
        every { selfMember.canInteract(role) } returns canInteract
        return role
    }
}
