package database.persistence.impl

import database.dto.EncounterDto
import database.persistence.EncounterPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@Transactional
class DefaultEncounterPersistence : EncounterPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(encounter: EncounterDto): EncounterDto {
        val now = LocalDateTime.now()
        encounter.updatedAt = now
        return if (encounter.id == 0L) {
            encounter.createdAt = now
            entityManager.persist(encounter)
            entityManager.flush()
            encounter
        } else {
            val merged = entityManager.merge(encounter)
            entityManager.flush()
            merged
        }
    }

    override fun getById(id: Long): EncounterDto? {
        val q = entityManager.createNamedQuery("EncounterDto.getById", EncounterDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listByDm(dmDiscordId: Long): List<EncounterDto> {
        val q = entityManager.createNamedQuery("EncounterDto.getByDm", EncounterDto::class.java)
        q.setParameter("dmDiscordId", dmDiscordId)
        return q.resultList
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("EncounterDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}
