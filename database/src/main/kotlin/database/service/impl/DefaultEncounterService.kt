package database.service.impl

import database.dto.EncounterDto
import database.persistence.EncounterPersistence
import database.service.EncounterService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultEncounterService(
    private val encounterPersistence: EncounterPersistence
) : EncounterService {

    override fun save(encounter: EncounterDto): EncounterDto =
        encounterPersistence.save(encounter)

    override fun getById(id: Long): EncounterDto? =
        encounterPersistence.getById(id)

    override fun listByDm(dmDiscordId: Long): List<EncounterDto> =
        encounterPersistence.listByDm(dmDiscordId)

    override fun deleteById(id: Long) =
        encounterPersistence.deleteById(id)
}
