package web.service

import database.dto.GuildTitleRoleDto
import database.dto.TitleDto
import database.service.GuildTitleRoleService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.RoleAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TitleRoleServiceTest {

    private lateinit var guildTitleRoleService: GuildTitleRoleService
    private lateinit var service: TitleRoleService
    private lateinit var guild: Guild
    private lateinit var selfMember: SelfMember
    private lateinit var member: Member

    private val guildId = 42L
    private val titleId = 7L

    @BeforeEach
    fun setup() {
        guildTitleRoleService = mockk(relaxed = true)
        service = TitleRoleService(guildTitleRoleService)
        guild = mockk(relaxed = true)
        selfMember = mockk(relaxed = true)
        member = mockk(relaxed = true)
        every { guild.idLong } returns guildId
        every { guild.selfMember } returns selfMember
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
    }

    private fun role(id: Long, name: String = "role-$id"): Role {
        val r = mockk<Role>(relaxed = true)
        every { r.idLong } returns id
        every { r.name } returns name
        return r
    }

    @Test
    fun `equip returns error when bot lacks Manage Roles`() {
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns false
        val title = TitleDto(id = titleId, label = "A", cost = 100L)
        val result = service.equip(guild, member, title, emptySet())
        assertTrue(result is TitleRoleResult.Error)
        assertTrue((result as TitleRoleResult.Error).message.contains("Manage Roles"))
    }

    @Test
    fun `equip reuses existing role mapping when the role still exists`() {
        val existingRole = role(200L, "⭐ Comrade")
        every { guildTitleRoleService.get(guildId, titleId) } returns GuildTitleRoleDto(
            guildId = guildId, titleId = titleId, discordRoleId = 200L
        )
        every { guild.getRoleById(200L) } returns existingRole
        every { member.roles } returns emptyList()

        val title = TitleDto(id = titleId, label = "⭐ Comrade", cost = 100L)
        val result = service.equip(guild, member, title, emptySet())

        assertEquals(TitleRoleResult.Ok, result)
        verify(exactly = 0) { guild.createRole() }
        verify(exactly = 1) { guild.addRoleToMember(member, existingRole) }
    }

    @Test
    fun `equip creates new role and persists mapping when none exists`() {
        every { guildTitleRoleService.get(guildId, titleId) } returns null
        val newRole = role(300L, "⭐ Comrade")
        val roleAction = mockk<RoleAction>(relaxed = true)
        every { guild.createRole() } returns roleAction
        every { roleAction.setName(any()) } returns roleAction
        every { roleAction.setColor(any<java.awt.Color>()) } returns roleAction
        every { roleAction.setHoisted(any()) } returns roleAction
        every { roleAction.setMentionable(any()) } returns roleAction
        every { roleAction.setPermissions(any<Long>()) } returns roleAction
        every { roleAction.complete() } returns newRole
        every { member.roles } returns emptyList()

        val title = TitleDto(id = titleId, label = "⭐ Comrade", cost = 100L, colorHex = "#F1C40F", hoisted = false)
        val result = service.equip(guild, member, title, emptySet())

        assertEquals(TitleRoleResult.Ok, result)
        val saved = slot<GuildTitleRoleDto>()
        verify(exactly = 1) { guildTitleRoleService.save(capture(saved)) }
        assertEquals(guildId, saved.captured.guildId)
        assertEquals(titleId, saved.captured.titleId)
        assertEquals(300L, saved.captured.discordRoleId)
    }

    @Test
    fun `equip removes previously equipped title role when owned`() {
        val oldRole = role(100L, "old")
        val newRole = role(200L, "new")
        every { guildTitleRoleService.get(guildId, titleId) } returns GuildTitleRoleDto(
            guildId = guildId, titleId = titleId, discordRoleId = 200L
        )
        every { guild.getRoleById(200L) } returns newRole
        every { guildTitleRoleService.listByGuild(guildId) } returns listOf(
            GuildTitleRoleDto(guildId = guildId, titleId = 5L, discordRoleId = 100L),
            GuildTitleRoleDto(guildId = guildId, titleId = titleId, discordRoleId = 200L)
        )
        every { guild.getRoleById(100L) } returns oldRole
        every { member.roles } returns listOf(oldRole)

        val title = TitleDto(id = titleId, label = "new", cost = 100L)
        val result = service.equip(guild, member, title, ownedTitleIds = setOf(5L, titleId))

        assertEquals(TitleRoleResult.Ok, result)
        verify(exactly = 1) { guild.removeRoleFromMember(member, oldRole) }
        verify(exactly = 1) { guild.addRoleToMember(member, newRole) }
    }

    @Test
    fun `equip surfaces hierarchy exception from JDA`() {
        val existingRole = role(200L, "⭐")
        every { guildTitleRoleService.get(guildId, titleId) } returns GuildTitleRoleDto(
            guildId = guildId, titleId = titleId, discordRoleId = 200L
        )
        every { guild.getRoleById(200L) } returns existingRole
        every { member.roles } returns emptyList()
        val action = mockk<AuditableRestAction<*>>(relaxed = true)
        @Suppress("UNCHECKED_CAST")
        every { guild.addRoleToMember(member, existingRole) } returns action as AuditableRestAction<Void>
        every { action.complete() } throws HierarchyException("too high")

        val title = TitleDto(id = titleId, label = "⭐", cost = 100L)
        val result = service.equip(guild, member, title, emptySet())

        assertTrue(result is TitleRoleResult.Error)
        assertTrue((result as TitleRoleResult.Error).message.contains("hierarchy"))
    }

    @Test
    fun `equip deletes stale mapping when referenced role is gone and creates a fresh one`() {
        every { guildTitleRoleService.get(guildId, titleId) } returns GuildTitleRoleDto(
            guildId = guildId, titleId = titleId, discordRoleId = 999L
        )
        every { guild.getRoleById(999L) } returns null
        val newRole = role(500L, "fresh")
        val roleAction = mockk<RoleAction>(relaxed = true)
        every { guild.createRole() } returns roleAction
        every { roleAction.setName(any()) } returns roleAction
        every { roleAction.setColor(any<java.awt.Color>()) } returns roleAction
        every { roleAction.setHoisted(any()) } returns roleAction
        every { roleAction.setMentionable(any()) } returns roleAction
        every { roleAction.setPermissions(any<Long>()) } returns roleAction
        every { roleAction.complete() } returns newRole
        every { member.roles } returns emptyList()

        val title = TitleDto(id = titleId, label = "fresh", cost = 100L)
        val result = service.equip(guild, member, title, emptySet())

        assertEquals(TitleRoleResult.Ok, result)
        verify(exactly = 1) { guildTitleRoleService.delete(guildId, titleId) }
    }

    @Test
    fun `unequip removes all owned title roles on the member`() {
        val roleA = role(100L)
        val roleB = role(200L)
        every { guildTitleRoleService.listByGuild(guildId) } returns listOf(
            GuildTitleRoleDto(guildId = guildId, titleId = 1L, discordRoleId = 100L),
            GuildTitleRoleDto(guildId = guildId, titleId = 2L, discordRoleId = 200L)
        )
        every { guild.getRoleById(100L) } returns roleA
        every { guild.getRoleById(200L) } returns roleB
        every { member.roles } returns listOf(roleA, roleB)

        val result = service.unequip(guild, member, setOf(1L, 2L))

        assertEquals(TitleRoleResult.Ok, result)
        verify(exactly = 1) { guild.removeRoleFromMember(member, roleA) }
        verify(exactly = 1) { guild.removeRoleFromMember(member, roleB) }
    }

    @Test
    fun `unequip is a no-op when user owns no titles`() {
        val result = service.unequip(guild, member, emptySet())
        assertEquals(TitleRoleResult.Ok, result)
        verify(exactly = 0) { guild.removeRoleFromMember(any<Member>(), any<Role>()) }
    }
}
