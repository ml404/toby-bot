package database.persistence.economy.impl

import database.dto.economy.TobyCoinJackpotWinnerDto
import database.persistence.economy.TobyCoinJackpotWinnerPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTobyCoinJackpotWinnerPersistence : TobyCoinJackpotWinnerPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(guildId: Long, discordId: Long): TobyCoinJackpotWinnerDto? {
        val q: TypedQuery<TobyCoinJackpotWinnerDto> = entityManager.createNamedQuery(
            "TobyCoinJackpotWinnerDto.get", TobyCoinJackpotWinnerDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("discordId", discordId)
        return q.resultList.firstOrNull()
    }

    override fun upsert(winner: TobyCoinJackpotWinnerDto): TobyCoinJackpotWinnerDto {
        val existing = get(winner.guildId, winner.discordId)
        return if (existing == null) {
            entityManager.persist(winner)
            entityManager.flush()
            winner
        } else {
            existing.lastWonAt = winner.lastWonAt
            existing.lastWonAmount = winner.lastWonAmount
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }
}
