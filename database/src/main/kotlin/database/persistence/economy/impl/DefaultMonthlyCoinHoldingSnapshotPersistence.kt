package database.persistence.economy.impl

import database.dto.economy.MonthlyCoinHoldingSnapshotDto
import database.dto.economy.MonthlyCoinHoldingSnapshotId
import database.persistence.economy.MonthlyCoinHoldingSnapshotPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultMonthlyCoinHoldingSnapshotPersistence : MonthlyCoinHoldingSnapshotPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listForGuildDate(guildId: Long, snapshotDate: LocalDate): List<MonthlyCoinHoldingSnapshotDto> {
        return entityManager.createQuery(
            "select s from MonthlyCoinHoldingSnapshotDto s " +
                "where s.guildId = :guildId and s.snapshotDate = :snapshotDate",
            MonthlyCoinHoldingSnapshotDto::class.java
        ).setParameter("guildId", guildId)
            .setParameter("snapshotDate", snapshotDate)
            .resultList
    }

    override fun upsertIfMissing(dto: MonthlyCoinHoldingSnapshotDto): MonthlyCoinHoldingSnapshotDto {
        val existing = entityManager.find(
            MonthlyCoinHoldingSnapshotDto::class.java,
            MonthlyCoinHoldingSnapshotId(dto.discordId, dto.guildId, dto.snapshotDate, dto.coin)
        )
        if (existing != null) return existing
        entityManager.persist(dto)
        entityManager.flush()
        return dto
    }
}
