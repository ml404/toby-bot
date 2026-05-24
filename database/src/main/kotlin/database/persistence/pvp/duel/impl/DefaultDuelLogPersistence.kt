package database.persistence.pvp.duel.impl

import database.dto.DuelLogDto
import database.persistence.pvp.duel.DuelLogPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultDuelLogPersistence : DuelLogPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun insert(row: DuelLogDto): DuelLogDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }
}
