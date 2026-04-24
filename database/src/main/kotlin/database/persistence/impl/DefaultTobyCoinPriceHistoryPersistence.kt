package database.persistence.impl

import database.dto.TobyCoinPricePointDto
import database.persistence.TobyCoinPriceHistoryPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional
class DefaultTobyCoinPriceHistoryPersistence : TobyCoinPriceHistoryPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun append(point: TobyCoinPricePointDto): TobyCoinPricePointDto {
        entityManager.persist(point)
        entityManager.flush()
        return point
    }

    override fun listSince(guildId: Long, since: Instant): List<TobyCoinPricePointDto> {
        val q: TypedQuery<TobyCoinPricePointDto> = entityManager.createNamedQuery(
            "TobyCoinPricePointDto.listSince", TobyCoinPricePointDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("since", since)
        return q.resultList
    }

    override fun listAll(guildId: Long): List<TobyCoinPricePointDto> {
        val q: TypedQuery<TobyCoinPricePointDto> = entityManager.createNamedQuery(
            "TobyCoinPricePointDto.listAll", TobyCoinPricePointDto::class.java
        )
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun deleteOlderThan(cutoff: Instant): Int {
        val q = entityManager.createNamedQuery("TobyCoinPricePointDto.deleteOlderThan")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }
}
