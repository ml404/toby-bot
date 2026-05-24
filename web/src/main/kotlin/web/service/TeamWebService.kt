package web.service

import database.dto.TeamPresetDto
import database.dto.TeamSplitSessionDto
import database.service.guild.TeamPresetService
import database.service.guild.TeamSplitSessionService
import database.service.guild.decodeAssignments
import database.service.guild.decodeTeamNames
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership
import java.time.Instant

/**
 * Backing service for the `/teams` web UI. Wraps the database service
 * pair (presets + recent sessions) and adds the same guild-scoping
 * checks Excuses uses, so the controller can rely on
 * [WebGuildAccess.requireForPage]`(check = service::isMember, ...)`.
 *
 * Member-list and mutual-guild fetches both delegate to
 * [IntroWebService] to share its caches and rate-limit handling.
 */
@Service
class TeamWebService(
    private val teamPresetService: TeamPresetService,
    private val teamSplitSessionService: TeamSplitSessionService,
    private val introWebService: IntroWebService,
    private val guildMembership: GuildMembership,
    private val jda: JDA,
) {
    companion object {
        const val PRESET_NAME_MAX = 64
        const val MAX_MEMBERS_PER_PRESET = 100
        const val RECENT_SESSIONS_LIMIT = 10
    }

    fun isMember(discordId: Long, guildId: Long): Boolean =
        guildMembership.isMember(discordId, guildId)

    fun getMutualGuilds(accessToken: String): List<GuildInfo> =
        introWebService.getMutualGuilds(accessToken)

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun getGuildMembers(guildId: Long): List<MemberInfo> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        return guild.members
            .filter { !it.user.isBot }
            .map { MemberInfo(it.id, it.effectiveName, it.effectiveAvatarUrl) }
            .sortedBy { it.name.lowercase() }
    }

    fun listPresets(guildId: Long): List<TeamPresetViewModel> {
        val guild = jda.getGuildById(guildId)
        return teamPresetService.listForGuild(guildId).map { dto ->
            val ids = dto.memberIdList
            val resolved = ids.map { id ->
                val name = guild?.getMemberById(id)?.effectiveName
                    ?: jda.getUserById(id)?.name
                    ?: "Unknown ($id)"
                MemberPreview(id.toString(), name)
            }
            TeamPresetViewModel(
                id = dto.id ?: 0L,
                name = dto.name,
                members = resolved,
                memberCount = ids.size,
                createdByDiscordId = dto.createdByDiscordId,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
            )
        }
    }

    fun listRecentSessions(guildId: Long): List<RecentSplitViewModel> {
        val guild = jda.getGuildById(guildId)
        return teamSplitSessionService.recentForGuild(guildId, RECENT_SESSIONS_LIMIT).map { dto ->
            val assignments = decodeAssignments(dto.assignments)
            val names = decodeTeamNames(dto.teamNames)
            val teams = assignments.mapIndexed { idx, ids ->
                val label = names.getOrNull(idx) ?: "Team ${idx + 1}"
                val resolved = ids.map { id ->
                    guild?.getMemberById(id)?.effectiveName
                        ?: jda.getUserById(id)?.name
                        ?: "Unknown ($id)"
                }
                TeamSnapshot(label, resolved)
            }
            val requesterName = guild?.getMemberById(dto.requesterDiscordId)?.effectiveName
                ?: jda.getUserById(dto.requesterDiscordId)?.name
                ?: "Unknown"
            RecentSplitViewModel(
                id = dto.id.toString(),
                createdAt = dto.createdAt,
                status = dto.lastAction,
                requester = requesterName,
                teams = teams,
            )
        }
    }

    /**
     * Create-or-replace a preset by name (case-insensitive). Returns
     * `null` on success or a user-facing error string on validation
     * failure. The requester's member-of-guild status is checked by the
     * controller layer before this is called.
     */
    fun upsertPreset(
        guildId: Long,
        rawName: String,
        rawMemberIds: List<String>,
        requesterDiscordId: Long,
    ): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return "Preset name is required."
        if (name.length > PRESET_NAME_MAX) {
            return "Preset name is too long (max $PRESET_NAME_MAX characters)."
        }
        val memberIds = rawMemberIds.mapNotNull { it.trim().toLongOrNull() }.distinct()
        if (memberIds.isEmpty()) return "Pick at least one member."
        if (memberIds.size > MAX_MEMBERS_PER_PRESET) {
            return "Too many members (max $MAX_MEMBERS_PER_PRESET per preset)."
        }
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val unknown = memberIds.filter { guild.getMemberById(it) == null }
        if (unknown.isNotEmpty()) {
            return "Some selected members are not in this server (${unknown.size} unknown)."
        }
        teamPresetService.upsertPreset(guildId, name, memberIds, requesterDiscordId)
        return null
    }

    fun deletePreset(id: Long, guildId: Long): String? {
        val existing = teamPresetService.getById(id) ?: return "Preset not found."
        if (existing.guildId != guildId) return "Preset not found."
        teamPresetService.deletePreset(id)
        return null
    }
}

data class TeamPresetViewModel(
    val id: Long,
    val name: String,
    val members: List<MemberPreview>,
    val memberCount: Int,
    val createdByDiscordId: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class MemberPreview(val id: String, val name: String)

data class TeamSnapshot(val name: String, val members: List<String>)

data class RecentSplitViewModel(
    val id: String,
    val createdAt: Instant?,
    val status: String,
    val requester: String,
    val teams: List<TeamSnapshot>,
)

/** Used by the controller for the preset-deleted ack JSON. */
data class TeamApiResult(val ok: Boolean, val error: String?)
