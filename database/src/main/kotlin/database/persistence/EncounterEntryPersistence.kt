package database.persistence

import database.dto.EncounterEntryDto

interface EncounterEntryPersistence {
    fun save(entry: EncounterEntryDto): EncounterEntryDto
    fun saveAll(entries: List<EncounterEntryDto>): List<EncounterEntryDto>
    fun getById(id: Long): EncounterEntryDto?
    fun listByEncounter(encounterId: Long): List<EncounterEntryDto>
    fun countByEncounter(encounterId: Long): Long
    fun maxSortOrder(encounterId: Long): Int
    fun deleteById(id: Long)
    fun deleteByEncounter(encounterId: Long)
}
