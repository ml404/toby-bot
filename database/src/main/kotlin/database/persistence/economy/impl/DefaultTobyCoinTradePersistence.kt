package database.persistence.economy.impl

import common.economy.Coin
import database.dto.economy.TobyCoinTradeDto
import database.persistence.economy.TobyCoinTradePersistence
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

    override fun listSince(guildId: Long, since: Instant, coin: Coin): List<TobyCoinTradeDto> {
        val q: TypedQuery<TobyCoinTradeDto> = entityManager.createNamedQuery(
            "TobyCoinTradeDto.listSince", TobyCoinTradeDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("coin", coin.symbol)
        q.setParameter("since", since)
        return q.resultList
    }

    override fun deleteOlderThan(cutoff: Instant): Int {
        val q = entityManager.createNamedQuery("TobyCoinTradeDto.deleteOlderThan")
        q.setParameter("cutoff", cutoff)
        return q.executeUpdate()
    }
}
