package database.service.impl

import database.persistence.CharacterSheetPersistence
import database.service.CharacterSheetService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultCharacterSheetService(
    private val characterSheetPersistence: CharacterSheetPersistence
) : CharacterSheetService {

    override fun saveSheet(characterId: Long, sheetJson: String) =
        characterSheetPersistence.saveOrUpdate(characterId, sheetJson)

    override fun getSheet(characterId: Long): String? =
        characterSheetPersistence.findById(characterId)

    override fun getCachedSheet(characterId: Long): CharacterSheetPersistence.CachedSheet? =
        characterSheetPersistence.findCached(characterId)
}
