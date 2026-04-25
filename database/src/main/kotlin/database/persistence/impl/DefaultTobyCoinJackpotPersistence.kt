package database.persistence.impl

import database.dto.TobyCoinJackpotDto
import database.persistence.TobyCoinJackpotPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTobyCoinJackpotPersistence : TobyCoinJackpotPersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getByGuild(guildId: Long): TobyCoinJackpotDto? {
        val q: TypedQuery<TobyCoinJackpotDto> = entityManager.createNamedQuery(
            "TobyCoinJackpotDto.getByGuild", TobyCoinJackpotDto::class.java
        )
        q.setParameter("guildId", guildId)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun getByGuildForUpdate(guildId: Long): TobyCoinJackpotDto? {
        return entityManager.find(
            TobyCoinJackpotDto::class.java,
            guildId,
            LockModeType.PESSIMISTIC_WRITE
        )
    }

    override fun upsert(jackpot: TobyCoinJackpotDto): TobyCoinJackpotDto {
        val existing = entityManager.find(TobyCoinJackpotDto::class.java, jackpot.guildId)
        val saved = if (existing == null) {
            entityManager.persist(jackpot)
            jackpot
        } else {
            entityManager.merge(jackpot)
        }
        entityManager.flush()
        return saved
    }
}
