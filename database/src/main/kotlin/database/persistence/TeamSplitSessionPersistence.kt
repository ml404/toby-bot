package database.persistence

import database.dto.TeamSplitSessionDto
import java.time.Instant
import java.util.UUID

interface TeamSplitSessionPersistence {
    fun save(dto: TeamSplitSessionDto): TeamSplitSessionDto
    fun getById(id: UUID): TeamSplitSessionDto?
    fun update(dto: TeamSplitSessionDto): TeamSplitSessionDto
    fun deleteById(id: UUID)
    fun deleteOlderThan(cutoff: Instant): Int
    fun recentForGuild(guildId: Long, limit: Int): List<TeamSplitSessionDto>
}
