package database.persistence

import java.time.LocalDateTime

interface CharacterSheetPersistence {
    fun saveOrUpdate(characterId: Long, sheetJson: String)
    fun findById(characterId: Long): String?
    fun findCached(characterId: Long): CachedSheet?

    data class CachedSheet(val sheetJson: String, val lastUpdated: LocalDateTime)
}
