package database.persistence.impl

import database.dto.UbiDailyDto
import database.persistence.UbiDailyPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultUbiDailyPersistence : UbiDailyPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(discordId: Long, guildId: Long, date: LocalDate): UbiDailyDto? {
        val q: TypedQuery<UbiDailyDto> =
            entityManager.createNamedQuery("UbiDailyDto.get", UbiDailyDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("grantDate", date)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: UbiDailyDto): UbiDailyDto {
        val existing = get(row.discordId, row.guildId, row.grantDate)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.creditsGranted = row.creditsGranted
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun sumGrantedInRangeByUser(guildId: Long, from: LocalDate, until: LocalDate): Map<Long, Long> {
        val q = entityManager.createNamedQuery("UbiDailyDto.sumGrantedInRangeByUser")
        q.setParameter("guildId", guildId)
        q.setParameter("from", from)
        q.setParameter("until", until)
        val rows = q.resultList as List<Array<Any?>>
        return rows.associate {
            (it[0] as Number).toLong() to ((it[1] as? Number)?.toLong() ?: 0L)
        }
    }
}
