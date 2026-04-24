package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import database.service.CharacterSheetService
import org.springframework.stereotype.Component

@Component
class DatabaseCharacterSheetProvider(
    private val characterSheetService: CharacterSheetService
) : CharacterSheetProvider {

    override suspend fun getCharacterSheet(characterId: Long): CharacterSheet? =
        CharacterSheetCodec.decodeOrNull(characterSheetService.getSheet(characterId))
}
