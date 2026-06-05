package database.persistence.user.impl

import database.dto.user.SharedCubeDto
import database.persistence.user.SharedCubePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultSharedCubePersistence : SharedCubePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(token: String): SharedCubeDto? {
        val q: TypedQuery<SharedCubeDto> =
            entityManager.createNamedQuery("SharedCubeDto.get", SharedCubeDto::class.java)
        q.setParameter("token", token)
        return q.resultList.firstOrNull()
    }

    override fun insert(row: SharedCubeDto): SharedCubeDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }
}
