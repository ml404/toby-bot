package database.service.impl

import database.dto.SessionNoteDto
import database.persistence.SessionNotePersistence
import database.service.SessionNoteService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultSessionNoteService(
    private val sessionNotePersistence: SessionNotePersistence
) : SessionNoteService {

    override fun createNote(note: SessionNoteDto): SessionNoteDto =
        sessionNotePersistence.createNote(note)

    override fun getNoteById(id: Long): SessionNoteDto? =
        sessionNotePersistence.getNoteById(id)

    override fun getNotesForCampaign(campaignId: Long): List<SessionNoteDto> =
        sessionNotePersistence.getNotesForCampaign(campaignId)

    override fun deleteNoteById(id: Long) =
        sessionNotePersistence.deleteNoteById(id)
}
