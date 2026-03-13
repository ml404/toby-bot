package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet

interface CharacterSheetProvider {
    suspend fun getCharacterSheet(characterId: Long): CharacterSheet?
}
