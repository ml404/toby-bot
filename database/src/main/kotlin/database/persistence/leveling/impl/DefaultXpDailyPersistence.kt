package database.persistence.leveling.impl

import database.dto.XpDailyDto
import database.persistence.leveling.XpDailyPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultXpDailyPersistence : XpDailyPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(discordId: Long, guildId: Long, date: LocalDate): XpDailyDto? {
        val q: TypedQuery<XpDailyDto> =
            entityManager.createNamedQuery("XpDailyDto.get", XpDailyDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("earnDate", date)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: XpDailyDto): XpDailyDto {
        val existing = get(row.discordId, row.guildId, row.earnDate)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.xpEarned = row.xpEarned
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
