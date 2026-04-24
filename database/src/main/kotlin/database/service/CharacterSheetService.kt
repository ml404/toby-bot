package database.service

import database.persistence.CharacterSheetPersistence

interface CharacterSheetService {
    fun saveSheet(characterId: Long, sheetJson: String)
    fun getSheet(characterId: Long): String?
    fun getCachedSheet(characterId: Long): CharacterSheetPersistence.CachedSheet?
}
