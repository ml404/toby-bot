package database.persistence.impl

import database.dto.MonthlyCreditSnapshotDto
import database.persistence.MonthlyCreditSnapshotPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultMonthlyCreditSnapshotPersistence : MonthlyCreditSnapshotPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(discordId: Long, guildId: Long, snapshotDate: LocalDate): MonthlyCreditSnapshotDto? {
        val q: TypedQuery<MonthlyCreditSnapshotDto> =
            entityManager.createNamedQuery("MonthlyCreditSnapshotDto.getForUserDate", MonthlyCreditSnapshotDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("discordId", discordId)
        q.setParameter("snapshotDate", snapshotDate)
        return q.resultList.firstOrNull()
    }

    override fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCreditSnapshotDto> {
        val q: TypedQuery<MonthlyCreditSnapshotDto> =
            entityManager.createNamedQuery("MonthlyCreditSnapshotDto.getForGuildDate", MonthlyCreditSnapshotDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("snapshotDate", snapshotDate)
        return q.resultList
    }

    override fun upsert(dto: MonthlyCreditSnapshotDto): MonthlyCreditSnapshotDto {
        val existing = get(dto.discordId, dto.guildId, dto.snapshotDate)
        return if (existing == null) {
            entityManager.persist(dto)
            entityManager.flush()
            dto
        } else {
            existing.socialCredit = dto.socialCredit
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
