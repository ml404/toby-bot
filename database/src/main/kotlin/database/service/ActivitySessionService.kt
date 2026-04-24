package database.service

import database.dto.ActivitySessionDto
import java.time.Instant

interface ActivitySessionService {
    fun openSession(session: ActivitySessionDto): ActivitySessionDto
    fun closeSession(session: ActivitySessionDto): ActivitySessionDto
    fun findOpen(discordId: Long, guildId: Long): ActivitySessionDto?
    fun findAllOpen(): List<ActivitySessionDto>
    fun findClosedBefore(cutoff: Instant): List<ActivitySessionDto>
    fun deleteClosedBefore(cutoff: Instant): Int
}
