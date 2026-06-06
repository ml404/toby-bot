package database.persistence.user.impl

import database.dto.user.CardPriceWatchDto
import database.persistence.saveOrMerge
import database.persistence.user.CardPriceWatchPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultCardPriceWatchPersistence : CardPriceWatchPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(watch: CardPriceWatchDto): CardPriceWatchDto =
        entityManager.saveOrMerge(watch, isNew = { it.id == null })

    override fun findById(id: Long): CardPriceWatchDto? =
        entityManager.find(CardPriceWatchDto::class.java, id)

    override fun listEnabled(): List<CardPriceWatchDto> {
        val q: TypedQuery<CardPriceWatchDto> = entityManager.createNamedQuery(
            "CardPriceWatchDto.listEnabled", CardPriceWatchDto::class.java
        )
        return q.resultList
    }

    override fun listByUser(discordId: Long): List<CardPriceWatchDto> {
        val q: TypedQuery<CardPriceWatchDto> = entityManager.createNamedQuery(
            "CardPriceWatchDto.listByUser", CardPriceWatchDto::class.java
        )
        q.setParameter("discordId", discordId)
        return q.resultList
    }

    override fun deleteById(id: Long): Boolean {
        val existing = entityManager.find(CardPriceWatchDto::class.java, id) ?: return false
        entityManager.remove(existing)
        entityManager.flush()
        return true
    }
}
