package database.persistence.impl

import database.dto.TobyCoinMarketDto
import database.persistence.TobyCoinMarketPersistence
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

    override fun getByGuild(guildId: Long): TobyCoinMarketDto? {
        val q: TypedQuery<TobyCoinMarketDto> = entityManager.createNamedQuery(
            "TobyCoinMarketDto.getByGuild", TobyCoinMarketDto::class.java
        )
        q.setParameter("guildId", guildId)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun getByGuildForUpdate(guildId: Long): TobyCoinMarketDto? {
        return entityManager.find(
            TobyCoinMarketDto::class.java,
            guildId,
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
        val existing = entityManager.find(TobyCoinMarketDto::class.java, market.guildId)
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
