package database.service

interface CharacterSheetService {
    fun saveSheet(characterId: Long, sheetJson: String)
    fun getSheet(characterId: Long): String?
}
