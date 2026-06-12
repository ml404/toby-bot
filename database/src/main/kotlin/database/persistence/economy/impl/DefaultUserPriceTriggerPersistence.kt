package database.persistence.economy.impl

import common.economy.Coin
import database.dto.economy.UserPriceTriggerDto
import database.persistence.economy.UserPriceTriggerPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import database.persistence.saveOrMerge

@Repository
@Transactional
class DefaultUserPriceTriggerPersistence : UserPriceTriggerPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(trigger: UserPriceTriggerDto): UserPriceTriggerDto =
        entityManager.saveOrMerge(trigger, isNew = { it.id == null })

    override fun findById(id: Long): UserPriceTriggerDto? =
        entityManager.find(UserPriceTriggerDto::class.java, id)

    override fun listEnabledByGuildAndCoin(guildId: Long, coin: Coin): List<UserPriceTriggerDto> {
        val q: TypedQuery<UserPriceTriggerDto> = entityManager.createNamedQuery(
            "UserPriceTriggerDto.listEnabledByGuildAndCoin", UserPriceTriggerDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("coin", coin.symbol)
        return q.resultList
    }

    override fun listByUser(discordId: Long, guildId: Long): List<UserPriceTriggerDto> {
        val q: TypedQuery<UserPriceTriggerDto> = entityManager.createNamedQuery(
            "UserPriceTriggerDto.listByUser", UserPriceTriggerDto::class.java
        )
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun deleteById(id: Long): Boolean {
        val existing = entityManager.find(UserPriceTriggerDto::class.java, id) ?: return false
        entityManager.remove(existing)
        entityManager.flush()
        return true
    }
}
