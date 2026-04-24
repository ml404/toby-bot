package database.persistence.impl

import database.dto.CharacterSheetDto
import database.persistence.CharacterSheetPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
@Transactional
class DefaultCharacterSheetPersistence : CharacterSheetPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun saveOrUpdate(characterId: Long, sheetJson: String) {
        val existing = entityManager.find(CharacterSheetDto::class.java, characterId)
        if (existing != null) {
            existing.sheetJson = sheetJson
            existing.lastUpdated = LocalDateTime.now()
            entityManager.merge(existing)
        } else {
            entityManager.persist(CharacterSheetDto(characterId, sheetJson, LocalDateTime.now()))
        }
        entityManager.flush()
    }

    override fun findById(characterId: Long): String? =
        entityManager.find(CharacterSheetDto::class.java, characterId)?.sheetJson

    override fun findCached(characterId: Long): CharacterSheetPersistence.CachedSheet? =
        entityManager.find(CharacterSheetDto::class.java, characterId)?.let {
            CharacterSheetPersistence.CachedSheet(it.sheetJson, it.lastUpdated)
        }
}
