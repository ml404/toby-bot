package database.service.activity.impl

import database.dto.activity.InstallEventDto
import database.dto.activity.InstallEventType
import database.persistence.activity.InstallEventPersistence
import database.service.activity.InstallEventService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultInstallEventService @Autowired constructor(
    private val persistence: InstallEventPersistence,
) : InstallEventService {

    override fun recordJoin(guildId: Long, occurredAt: Instant) =
        persistence.record(guildId, InstallEventType.JOIN, occurredAt)

    override fun recordLeave(guildId: Long, occurredAt: Instant) =
        persistence.record(guildId, InstallEventType.LEAVE, occurredAt)

    override fun countByType(type: InstallEventType): Long = persistence.countByType(type)

    override fun countByTypeSince(type: InstallEventType, since: Instant): Long =
        persistence.countByTypeSince(type, since)

    override fun findSince(since: Instant): List<InstallEventDto> = persistence.findSince(since)
}
