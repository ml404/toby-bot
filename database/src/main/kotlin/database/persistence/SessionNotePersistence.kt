package database.persistence

import database.dto.SessionNoteDto

interface SessionNotePersistence {
    fun createNote(note: SessionNoteDto): SessionNoteDto
    fun getNoteById(id: Long): SessionNoteDto?
    fun getNotesForCampaign(campaignId: Long): List<SessionNoteDto>
    fun deleteNoteById(id: Long)
}
