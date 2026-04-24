package web.service

import common.logging.DiscordLogger
import database.dto.GuildTitleRoleDto
import database.dto.TitleDto
import database.service.GuildTitleRoleService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.awt.Color

sealed class TitleRoleResult {
    data object Ok : TitleRoleResult()
    data class Error(val message: String) : TitleRoleResult()
}

@Service
class TitleRoleService @Autowired constructor(
    private val guildTitleRoleService: GuildTitleRoleService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun equip(guild: Guild, member: Member, title: TitleDto, ownedTitleIds: Set<Long>): TitleRoleResult {
        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            return TitleRoleResult.Error("Bot needs **Manage Roles** permission to grant title roles.")
        }

        val role = runCatching { ensureTitleRole(guild, title) }
            .getOrElse { e ->
                return TitleRoleResult.Error(
                    (e as? IllegalStateException)?.message
                        ?: "Could not create or fetch the title role: ${e.message}"
                )
            }

        return runCatching {
            removeOtherTitleRoles(guild, member, ownedTitleIds, exceptTitleId = title.id)
            guild.addRoleToMember(member, role).complete()
            TitleRoleResult.Ok
        }.getOrElse { e ->
            when (e) {
                is HierarchyException -> TitleRoleResult.Error(
                    "The bot's role must sit **above** the title roles in the role hierarchy."
                )
                is InsufficientPermissionException -> TitleRoleResult.Error(
                    "Bot is missing a permission required to assign the title role: ${e.message}"
                )
                else -> TitleRoleResult.Error("Could not assign title role: ${e.message}")
            }
        }
    }

    fun unequip(guild: Guild, member: Member, ownedTitleIds: Set<Long>): TitleRoleResult {
        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            return TitleRoleResult.Error("Bot needs **Manage Roles** permission to remove title roles.")
        }
        return runCatching {
            removeOtherTitleRoles(guild, member, ownedTitleIds, exceptTitleId = null)
            TitleRoleResult.Ok
        }.getOrElse { TitleRoleResult.Error("Could not remove title role: ${it.message}") }
    }

    private fun removeOtherTitleRoles(guild: Guild, member: Member, ownedTitleIds: Set<Long>, exceptTitleId: Long?) {
        if (ownedTitleIds.isEmpty()) return
        val guildRoles = guildTitleRoleService.listByGuild(guild.idLong)
            .filter { it.titleId in ownedTitleIds && it.titleId != exceptTitleId }
        guildRoles.forEach { mapping ->
            val role = guild.getRoleById(mapping.discordRoleId) ?: return@forEach
            if (member.roles.any { it.idLong == role.idLong }) {
                runCatching { guild.removeRoleFromMember(member, role).complete() }
                    .onFailure { logger.warn("Could not remove title role ${role.name}: ${it.message}") }
            }
        }
    }

    private fun ensureTitleRole(guild: Guild, title: TitleDto): Role {
        val titleId = title.id ?: error("Title has no id.")
        val mapping = guildTitleRoleService.get(guild.idLong, titleId)
        if (mapping != null) {
            val existing = guild.getRoleById(mapping.discordRoleId)
            if (existing != null) return existing
            guildTitleRoleService.delete(guild.idLong, titleId)
        }

        val color = parseColor(title.colorHex)
        val newRole = runCatching {
            guild.createRole()
                .setName(title.label)
                .setColor(color)
                .setHoisted(title.hoisted)
                .setMentionable(false)
                .setPermissions(0L)
                .complete()
        }.getOrElse { e ->
            when (e) {
                is InsufficientPermissionException -> error(
                    "Bot needs **Manage Roles** permission to create title roles."
                )
                else -> error("Could not create title role: ${e.message}")
            }
        }

        guildTitleRoleService.save(
            GuildTitleRoleDto(
                guildId = guild.idLong,
                titleId = titleId,
                discordRoleId = newRole.idLong
            )
        )
        return newRole
    }

    private fun parseColor(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.removePrefix("#")
        return runCatching { Color(cleaned.toInt(16)) }.getOrNull()
    }
}
