package database.service.guild.impl

import database.dto.TeamSplitSessionDto
import database.persistence.guild.TeamSplitSessionPersistence
import database.service.guild.TeamSplitSessionService
import database.service.guild.encodeAssignments
import database.service.guild.encodeTeamNames
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class DefaultTeamSplitSessionService : TeamSplitSessionService {
    @Autowired
    private lateinit var teamSplitSessionPersistence: TeamSplitSessionPersistence

    override fun createSession(
        guildId: Long,
        requesterDiscordId: Long,
        memberIds: List<Long>,
        teamCount: Int,
        assignments: List<List<Long>>,
        teamNames: List<String>,
    ): TeamSplitSessionDto {
        val dto = TeamSplitSessionDto(
            id = UUID.randomUUID(),
            guildId = guildId,
            requesterDiscordId = requesterDiscordId,
            memberIds = memberIds.joinToString(","),
            teamCount = teamCount,
            assignments = encodeAssignments(assignments),
            teamNames = encodeTeamNames(teamNames),
            lastAction = TeamSplitSessionDto.ACTION_CREATED,
        )
        return teamSplitSessionPersistence.save(dto)
    }

    override fun getSession(id: UUID): TeamSplitSessionDto? =
        teamSplitSessionPersistence.getById(id)

    override fun updateAssignments(id: UUID, assignments: List<List<Long>>): TeamSplitSessionDto? {
        val existing = teamSplitSessionPersistence.getById(id) ?: return null
        existing.assignments = encodeAssignments(assignments)
        existing.lastAction = TeamSplitSessionDto.ACTION_REROLLED
        return teamSplitSessionPersistence.update(existing)
    }

    override fun markConfirmed(id: UUID): TeamSplitSessionDto? {
        val existing = teamSplitSessionPersistence.getById(id) ?: return null
        existing.lastAction = TeamSplitSessionDto.ACTION_CONFIRMED
        return teamSplitSessionPersistence.update(existing)
    }

    override fun markCancelled(id: UUID): TeamSplitSessionDto? {
        val existing = teamSplitSessionPersistence.getById(id) ?: return null
        existing.lastAction = TeamSplitSessionDto.ACTION_CANCELLED
        return teamSplitSessionPersistence.update(existing)
    }

    override fun purgeOlderThan(maxAge: Duration): Int =
        teamSplitSessionPersistence.deleteOlderThan(Instant.now().minus(maxAge))

    override fun recentForGuild(guildId: Long, limit: Int): List<TeamSplitSessionDto> =
        teamSplitSessionPersistence.recentForGuild(guildId, limit)
}
