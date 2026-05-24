package database.persistence.user.impl

import database.dto.UserNotificationPrefDto
import database.persistence.user.UserNotificationPrefPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultUserNotificationPrefPersistence : UserNotificationPrefPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(
        discordId: Long,
        guildId: Long,
        channelKind: String,
        surface: String,
    ): UserNotificationPrefDto? {
        val q: TypedQuery<UserNotificationPrefDto> =
            entityManager.createNamedQuery("UserNotificationPrefDto.get", UserNotificationPrefDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("channelKind", channelKind)
        q.setParameter("surface", surface)
        return q.resultList.firstOrNull()
    }

    override fun listByUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto> {
        val q: TypedQuery<UserNotificationPrefDto> =
            entityManager.createNamedQuery("UserNotificationPrefDto.getByUser", UserNotificationPrefDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun upsert(row: UserNotificationPrefDto): UserNotificationPrefDto {
        val existing = get(row.discordId, row.guildId, row.channelKind, row.surface)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.optIn = row.optIn
            existing.updatedAt = row.updatedAt
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
