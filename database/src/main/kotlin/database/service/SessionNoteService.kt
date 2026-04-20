package database.service

import database.dto.SessionNoteDto

interface SessionNoteService {
    fun createNote(note: SessionNoteDto): SessionNoteDto
    fun getNoteById(id: Long): SessionNoteDto?
    fun getNotesForCampaign(campaignId: Long): List<SessionNoteDto>
    fun deleteNoteById(id: Long)
}
