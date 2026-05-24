package database.service.activity.impl

import database.dto.ActivitySessionDto
import database.persistence.activity.ActivitySessionPersistence
import database.service.activity.ActivitySessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultActivitySessionService @Autowired constructor(
    private val persistence: ActivitySessionPersistence
) : ActivitySessionService {
    override fun openSession(session: ActivitySessionDto) = persistence.openSession(session)
    override fun closeSession(session: ActivitySessionDto) = persistence.closeSession(session)
    override fun findOpen(discordId: Long, guildId: Long) = persistence.findOpen(discordId, guildId)
    override fun findAllOpen() = persistence.findAllOpen()
    override fun findClosedBefore(cutoff: Instant) = persistence.findClosedBefore(cutoff)
    override fun deleteClosedBefore(cutoff: Instant) = persistence.deleteClosedBefore(cutoff)
}
