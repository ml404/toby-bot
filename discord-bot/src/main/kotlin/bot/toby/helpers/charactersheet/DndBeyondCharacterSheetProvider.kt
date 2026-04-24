package bot.toby.helpers.charactersheet

import bot.toby.dto.web.dnd.CharacterSheet
import bot.toby.helpers.charactersheet.CharacterSheetProvider.FetchResult
import database.persistence.CharacterSheetPersistence
import database.service.CharacterSheetService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Primary
@Component
class DndBeyondCharacterSheetProvider(
    private val fetcher: DndBeyondCharacterFetcher,
    private val characterSheetService: CharacterSheetService,
    @param:Value("\${dnd.beyond.sheet.ttl-seconds:3600}") private val ttlSeconds: Long = 3600,
) : CharacterSheetProvider {

    private val logger = LoggerFactory.getLogger(DndBeyondCharacterSheetProvider::class.java)

    override suspend fun getCharacterSheet(characterId: Long): CharacterSheet? {
        val cached = characterSheetService.getCachedSheet(characterId)
        if (cached != null && isFresh(cached)) {
            return CharacterSheetCodec.decodeOrNull(cached.sheetJson)
        }

        return when (val result = fetcher.fetch(characterId)) {
            is FetchResult.Success -> {
                characterSheetService.saveSheet(characterId, result.rawJson)
                result.sheet
            }
            else -> {
                if (cached != null) {
                    logger.debug(
                        "Serving stale cache for character id={} after fetch result={}",
                        characterId, result::class.simpleName
                    )
                }
                CharacterSheetCodec.decodeOrNull(cached?.sheetJson)
            }
        }
    }

    override suspend fun fetchCharacterSheet(characterId: Long): FetchResult {
        val result = fetcher.fetch(characterId)
        if (result is FetchResult.Success) {
            characterSheetService.saveSheet(characterId, result.rawJson)
        }
        return result
    }

    private fun isFresh(cached: CharacterSheetPersistence.CachedSheet): Boolean {
        if (ttlSeconds <= 0) return false
        val age = Duration.between(cached.lastUpdated, LocalDateTime.now())
        return age.seconds < ttlSeconds
    }
}
