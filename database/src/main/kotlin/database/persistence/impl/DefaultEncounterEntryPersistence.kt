package database.persistence.impl

import database.dto.EncounterEntryDto
import database.persistence.EncounterEntryPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@Transactional
class DefaultEncounterEntryPersistence : EncounterEntryPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(entry: EncounterEntryDto): EncounterEntryDto {
        return entityManager.saveOrMerge(entry, isNew = { it.id == 0L }) {
            it.createdAt = LocalDateTime.now()
        }
    }

    override fun saveAll(entries: List<EncounterEntryDto>): List<EncounterEntryDto> {
        val saved = entries.map { entry ->
            if (entry.id == 0L) {
                entry.createdAt = LocalDateTime.now()
                entityManager.persist(entry)
                entry
            } else {
                entityManager.merge(entry)
            }
        }
        entityManager.flush()
        return saved
    }

    override fun getById(id: Long): EncounterEntryDto? {
        val q = entityManager.createNamedQuery("EncounterEntryDto.getById", EncounterEntryDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun listByEncounter(encounterId: Long): List<EncounterEntryDto> {
        val q = entityManager.createNamedQuery("EncounterEntryDto.listByEncounter", EncounterEntryDto::class.java)
        q.setParameter("encounterId", encounterId)
        return q.resultList
    }

    override fun countByEncounter(encounterId: Long): Long {
        val q = entityManager.createNamedQuery("EncounterEntryDto.countByEncounter")
        q.setParameter("encounterId", encounterId)
        return (q.singleResult as Number).toLong()
    }

    override fun maxSortOrder(encounterId: Long): Int {
        val q = entityManager.createNamedQuery("EncounterEntryDto.maxSortOrder")
        q.setParameter("encounterId", encounterId)
        return (q.singleResult as Number).toInt()
    }

    override fun deleteById(id: Long) {
        val q = entityManager.createNamedQuery("EncounterEntryDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }

    override fun deleteByEncounter(encounterId: Long) {
        val q = entityManager.createNamedQuery("EncounterEntryDto.deleteByEncounter")
        q.setParameter("encounterId", encounterId)
        q.executeUpdate()
    }
}
