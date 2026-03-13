package database.persistence

interface CharacterSheetPersistence {
    fun saveOrUpdate(characterId: Long, sheetJson: String)
    fun findById(characterId: Long): String?
}
