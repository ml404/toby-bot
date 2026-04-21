package database.persistence

import database.dto.EncounterDto

interface EncounterPersistence {
    fun save(encounter: EncounterDto): EncounterDto
    fun getById(id: Long): EncounterDto?
    fun listByDm(dmDiscordId: Long): List<EncounterDto>
    fun deleteById(id: Long)
}
