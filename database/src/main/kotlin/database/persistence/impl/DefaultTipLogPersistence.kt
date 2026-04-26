package database.persistence.impl

import database.dto.TipLogDto
import database.persistence.TipLogPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultTipLogPersistence : TipLogPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun insert(row: TipLogDto): TipLogDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }
}
