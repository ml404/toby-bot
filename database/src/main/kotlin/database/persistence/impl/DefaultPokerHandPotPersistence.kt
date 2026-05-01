package database.persistence.impl

import database.dto.PokerHandPotDto
import database.persistence.PokerHandPotPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultPokerHandPotPersistence : PokerHandPotPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun insert(row: PokerHandPotDto): PokerHandPotDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }

    override fun findByHandLogId(handLogId: Long): List<PokerHandPotDto> =
        entityManager.createQuery(
            "SELECT p FROM PokerHandPotDto p WHERE p.handLogId = :id ORDER BY p.tierIndex ASC",
            PokerHandPotDto::class.java
        ).setParameter("id", handLogId).resultList
}
