package database.service

import database.dto.EncounterDto

interface EncounterService {
    fun save(encounter: EncounterDto): EncounterDto
    fun getById(id: Long): EncounterDto?
    fun listByDm(dmDiscordId: Long): List<EncounterDto>
    fun deleteById(id: Long)
}
