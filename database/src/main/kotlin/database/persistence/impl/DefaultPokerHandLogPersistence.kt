package database.persistence.impl

import database.dto.PokerHandLogDto
import database.persistence.PokerHandLogPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultPokerHandLogPersistence : PokerHandLogPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun insert(row: PokerHandLogDto): PokerHandLogDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }
}
