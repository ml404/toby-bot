package database.service.impl

import database.dto.EncounterEntryDto
import database.persistence.EncounterEntryPersistence
import database.service.EncounterEntryService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultEncounterEntryService(
    private val encounterEntryPersistence: EncounterEntryPersistence
) : EncounterEntryService {

    override fun save(entry: EncounterEntryDto): EncounterEntryDto =
        encounterEntryPersistence.save(entry)

    override fun saveAll(entries: List<EncounterEntryDto>): List<EncounterEntryDto> =
        encounterEntryPersistence.saveAll(entries)

    override fun getById(id: Long): EncounterEntryDto? =
        encounterEntryPersistence.getById(id)

    override fun listByEncounter(encounterId: Long): List<EncounterEntryDto> =
        encounterEntryPersistence.listByEncounter(encounterId)

    override fun countByEncounter(encounterId: Long): Long =
        encounterEntryPersistence.countByEncounter(encounterId)

    override fun maxSortOrder(encounterId: Long): Int =
        encounterEntryPersistence.maxSortOrder(encounterId)

    override fun deleteById(id: Long) =
        encounterEntryPersistence.deleteById(id)

    override fun deleteByEncounter(encounterId: Long) =
        encounterEntryPersistence.deleteByEncounter(encounterId)
}
