package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import org.springframework.stereotype.Component

@Component
class DnDBeyondCharacterSheetProvider(
    private val dndHelper: DnDHelper,
    private val httpHelper: HttpHelper
) : CharacterSheetProvider {

    override suspend fun getCharacterSheet(characterId: Long): CharacterSheet? =
        dndHelper.fetchCharacter(characterId, httpHelper)
}
