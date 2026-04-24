package database.persistence.impl

import database.dto.VoiceCreditDailyDto
import database.persistence.VoiceCreditDailyPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultVoiceCreditDailyPersistence : VoiceCreditDailyPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto? {
        val q: TypedQuery<VoiceCreditDailyDto> =
            entityManager.createNamedQuery("VoiceCreditDailyDto.get", VoiceCreditDailyDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        q.setParameter("earnDate", date)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto {
        val existing = get(row.discordId, row.guildId, row.earnDate)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.credits = row.credits
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
