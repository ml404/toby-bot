package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import com.google.gson.Gson
import database.service.CharacterSheetService
import org.springframework.stereotype.Component

@Component
class DatabaseCharacterSheetProvider(
    private val characterSheetService: CharacterSheetService
) : CharacterSheetProvider {

    private val gson = Gson()

    override suspend fun getCharacterSheet(characterId: Long): CharacterSheet? {
        val json = characterSheetService.getSheet(characterId) ?: return null
        return gson.fromJson(json, CharacterSheet::class.java)
    }
}
