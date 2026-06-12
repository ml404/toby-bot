package database.persistence.economy.impl

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinMarketId
import database.persistence.economy.TobyCoinMarketPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTobyCoinMarketPersistence : TobyCoinMarketPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getByGuild(guildId: Long, coin: Coin): TobyCoinMarketDto? {
        val q: TypedQuery<TobyCoinMarketDto> = entityManager.createNamedQuery(
            "TobyCoinMarketDto.getByGuildAndCoin", TobyCoinMarketDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("coin", coin.symbol)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun getByGuildForUpdate(guildId: Long, coin: Coin): TobyCoinMarketDto? {
        return entityManager.find(
            TobyCoinMarketDto::class.java,
            TobyCoinMarketId(guildId, coin.symbol),
            LockModeType.PESSIMISTIC_WRITE
        )
    }

    override fun listAll(): List<TobyCoinMarketDto> {
        val q: TypedQuery<TobyCoinMarketDto> = entityManager.createNamedQuery(
            "TobyCoinMarketDto.listAll", TobyCoinMarketDto::class.java
        )
        return q.resultList
    }

    override fun upsert(market: TobyCoinMarketDto): TobyCoinMarketDto {
        val existing = entityManager.find(
            TobyCoinMarketDto::class.java,
            TobyCoinMarketId(market.guildId, market.coin)
        )
        val saved = if (existing == null) {
            entityManager.persist(market)
            market
        } else {
            entityManager.merge(market)
        }
        entityManager.flush()
        return saved
    }
}
