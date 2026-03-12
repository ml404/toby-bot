package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import com.google.gson.Gson
import common.logging.DiscordLogger
import database.service.CharacterSheetService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Primary
@Component
class CompositeCharacterSheetProvider(
    private val dndbeyond: DnDBeyondCharacterSheetProvider,
    private val database: DatabaseCharacterSheetProvider,
    private val characterSheetService: CharacterSheetService
) : CharacterSheetProvider {

    private val gson = Gson()
    private val logger = DiscordLogger(this::class.java)

    override suspend fun getCharacterSheet(characterId: Long): CharacterSheet? {
        val apiResult = runCatching { dndbeyond.getCharacterSheet(characterId) }
        val sheet = apiResult.getOrNull()

        if (sheet != null) {
            characterSheetService.saveSheet(characterId, gson.toJson(sheet))
            return sheet
        }

        if (apiResult.isFailure) {
            logger.warn("D&D Beyond API failed for character $characterId; falling back to DB cache")
        }

        return database.getCharacterSheet(characterId)
    }
}
