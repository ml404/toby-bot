package database.persistence.user.impl

import database.dto.user.CubeListDto
import database.persistence.user.CubeListPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultCubeListPersistence : CubeListPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listForUser(discordId: Long): List<CubeListDto> {
        val q: TypedQuery<CubeListDto> =
            entityManager.createNamedQuery("CubeListDto.listForUser", CubeListDto::class.java)
        q.setParameter("discordId", discordId)
        return q.resultList
    }

    override fun get(discordId: Long, name: String): CubeListDto? {
        val q: TypedQuery<CubeListDto> =
            entityManager.createNamedQuery("CubeListDto.get", CubeListDto::class.java)
        q.setParameter("discordId", discordId)
        q.setParameter("name", name)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: CubeListDto): CubeListDto {
        val existing = get(row.discordId, row.name)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing.cards = row.cards
            existing.updatedAt = row.updatedAt
            entityManager.merge(existing)
            entityManager.flush()
            existing
        }
    }

    override fun delete(discordId: Long, name: String): Int {
        val q = entityManager.createNamedQuery("CubeListDto.deleteByUserAndName")
        q.setParameter("discordId", discordId)
        q.setParameter("name", name)
        return q.executeUpdate()
    }
}
