package database.persistence.impl

import database.dto.TobyCoinTradeDto
import database.persistence.TobyCoinTradePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional
class DefaultTobyCoinTradePersistence : TobyCoinTradePersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun record(trade: TobyCoinTradeDto): TobyCoinTradeDto {
        entityManager.persist(trade)
        entityManager.flush()
        return trade
    }

    override fun listSince(guildId: Long, since: Instant): List<TobyCoinTradeDto> {
        val q: TypedQuery<TobyCoinTradeDto> = entityManager.createNamedQuery(
            "TobyCoinTradeDto.listSince", TobyCoinTradeDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("since", since)
        return q.resultList
    }

    override fun deleteOlderThan(cutoff: Instant): Int {
        val q = entityManager.createNamedQuery("TobyCoinTradeDto.deleteOlderThan")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }
}
