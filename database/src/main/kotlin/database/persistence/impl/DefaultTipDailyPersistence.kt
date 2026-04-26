package database.persistence.impl

import database.dto.TipDailyDto
import database.persistence.TipDailyPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultTipDailyPersistence : TipDailyPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(senderDiscordId: Long, guildId: Long, date: LocalDate): TipDailyDto? {
        val q: TypedQuery<TipDailyDto> =
            entityManager.createNamedQuery("TipDailyDto.get", TipDailyDto::class.java)
        q.setParameter("senderDiscordId", senderDiscordId)
        q.setParameter("guildId", guildId)
        q.setParameter("tipDate", date)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: TipDailyDto): TipDailyDto {
        val existing = get(row.senderDiscordId, row.guildId, row.tipDate)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.creditsSent = row.creditsSent
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
