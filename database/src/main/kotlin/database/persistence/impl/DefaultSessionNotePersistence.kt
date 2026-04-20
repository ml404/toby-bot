package database.persistence.impl

import database.dto.SessionNoteDto
import database.persistence.SessionNotePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultSessionNotePersistence : SessionNotePersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun createNote(note: SessionNoteDto): SessionNoteDto {
        entityManager.persist(note)
        entityManager.flush()
        return note
    }

    override fun getNoteById(id: Long): SessionNoteDto? {
        val q = entityManager.createNamedQuery("SessionNoteDto.getById", SessionNoteDto::class.java)
        q.setParameter("id", id)
        return runCatching { q.singleResult }.getOrNull()
    }

    override fun getNotesForCampaign(campaignId: Long): List<SessionNoteDto> {
        val q = entityManager.createNamedQuery("SessionNoteDto.getByCampaign", SessionNoteDto::class.java)
        q.setParameter("campaignId", campaignId)
        return q.resultList
    }

    override fun deleteNoteById(id: Long) {
        val q = entityManager.createNamedQuery("SessionNoteDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}
