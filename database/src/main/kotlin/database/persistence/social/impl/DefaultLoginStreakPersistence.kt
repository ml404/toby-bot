package database.persistence.social.impl

import database.dto.LoginStreakDto
import database.persistence.social.LoginStreakPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultLoginStreakPersistence : LoginStreakPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(discordId: Long, guildId: Long): LoginStreakDto? {
        val q: TypedQuery<LoginStreakDto> =
            entityManager.createNamedQuery("LoginStreakDto.get", LoginStreakDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList.firstOrNull()
    }

    override fun findActiveStreaksDueForReminder(
        guildId: Long,
        today: LocalDate
    ): List<LoginStreakDto> {
        val q: TypedQuery<LoginStreakDto> =
            entityManager.createNamedQuery(
                "LoginStreakDto.findActiveStreaksDueForReminder",
                LoginStreakDto::class.java
            )
        q.setParameter("guildId", guildId)
        q.setParameter("today", today)
        return q.resultList
    }

    override fun upsert(row: LoginStreakDto): LoginStreakDto {
        val existing = get(row.discordId, row.guildId)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.currentStreak = row.currentStreak
            existing.longestStreak = row.longestStreak
            existing.lastClaimDate = row.lastClaimDate
            existing.totalClaims = row.totalClaims
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
