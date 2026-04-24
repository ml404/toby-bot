package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import database.dto.UserDto
import database.service.CharacterSheetService
import org.springframework.stereotype.Service

@Service
class InitiativeResolver(
    private val characterSheetService: CharacterSheetService
) {
    private val mapper = ObjectMapper()

    /** DEX modifier from the user's linked & cached character sheet, or null when unavailable. */
    fun resolve(userDto: UserDto): Int? {
        val characterId = userDto.dndBeyondCharacterId ?: return null
        val json = characterSheetService.getSheet(characterId) ?: return null
        return runCatching {
            val statsNode = mapper.readTree(json).get("stats") ?: return@runCatching null
            if (!statsNode.isArray) return@runCatching null
            val dexNode = statsNode.firstOrNull { it.get("id")?.asInt() == DEX_STAT_ID }
                ?: return@runCatching null
            val dexValue = dexNode.get("value")?.asInt() ?: 10
            Math.floorDiv(dexValue - 10, 2)
        }.getOrNull()
    }

    companion object {
        private const val DEX_STAT_ID = 2
    }
}
