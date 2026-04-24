package database.persistence.impl

import database.dto.ActivityMonthlyRollupDto
import database.dto.ActivityMonthlyRollupId
import database.persistence.ActivityMonthlyRollupPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultActivityMonthlyRollupPersistence : ActivityMonthlyRollupPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun addSeconds(
        discordId: Long,
        guildId: Long,
        monthStart: LocalDate,
        activityName: String,
        delta: Long
    ): ActivityMonthlyRollupDto {
        val id = ActivityMonthlyRollupId(discordId, guildId, monthStart, activityName)
        val existing = entityManager.find(ActivityMonthlyRollupDto::class.java, id)
        return if (existing == null) {
            val row = ActivityMonthlyRollupDto(
                discordId = discordId,
                guildId = guildId,
                monthStart = monthStart,
                activityName = activityName,
                seconds = delta
            )
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.seconds += delta
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    override fun forGuildMonth(guildId: Long, monthStart: LocalDate): List<ActivityMonthlyRollupDto> {
        val q: TypedQuery<ActivityMonthlyRollupDto> = entityManager.createNamedQuery(
            "ActivityMonthlyRollupDto.forGuildMonth", ActivityMonthlyRollupDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("monthStart", monthStart)
        return q.resultList
    }

    override fun forUser(guildId: Long, discordId: Long): List<ActivityMonthlyRollupDto> {
        val q: TypedQuery<ActivityMonthlyRollupDto> = entityManager.createNamedQuery(
            "ActivityMonthlyRollupDto.forUser", ActivityMonthlyRollupDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("discordId", discordId)
        return q.resultList
    }

    override fun forUserMonth(
        guildId: Long,
        discordId: Long,
        monthStart: LocalDate
    ): List<ActivityMonthlyRollupDto> {
        val q: TypedQuery<ActivityMonthlyRollupDto> = entityManager.createNamedQuery(
            "ActivityMonthlyRollupDto.forUserMonth", ActivityMonthlyRollupDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("discordId", discordId)
        q.setParameter("monthStart", monthStart)
        return q.resultList
    }

    override fun deleteBefore(cutoff: LocalDate): Int {
        val q = entityManager.createNamedQuery("ActivityMonthlyRollupDto.deleteBefore")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }
}
