package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet

interface CharacterSheetProvider {
    suspend fun getCharacterSheet(characterId: Long): CharacterSheet?

    suspend fun fetchCharacterSheet(characterId: Long): FetchResult = FetchResult.Unavailable()

    sealed class FetchResult {
        data class Success(val sheet: CharacterSheet, val rawJson: String) : FetchResult()
        object Forbidden : FetchResult()
        object NotFound : FetchResult()
        data class Unavailable(val cause: Throwable? = null) : FetchResult()
    }
}
