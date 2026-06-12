package database.persistence.economy.impl

import common.economy.Coin
import database.dto.economy.UserCoinHoldingDto
import database.dto.economy.UserCoinHoldingId
import database.persistence.economy.UserCoinHoldingPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultUserCoinHoldingPersistence : UserCoinHoldingPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getAmount(discordId: Long, guildId: Long, coin: Coin): Long {
        val row = entityManager.find(
            UserCoinHoldingDto::class.java,
            UserCoinHoldingId(discordId, guildId, coin.symbol)
        )
        return row?.amount ?: 0L
    }

    override fun getForUpdateOrCreate(discordId: Long, guildId: Long, coin: Coin): UserCoinHoldingDto {
        val id = UserCoinHoldingId(discordId, guildId, coin.symbol)
        val existing = entityManager.find(
            UserCoinHoldingDto::class.java, id, LockModeType.PESSIMISTIC_WRITE
        )
        if (existing != null) return existing
        val fresh = UserCoinHoldingDto(
            discordId = discordId, guildId = guildId, coin = coin.symbol, amount = 0L
        )
        entityManager.persist(fresh)
        entityManager.flush()
        return fresh
    }

    override fun save(holding: UserCoinHoldingDto): UserCoinHoldingDto {
        val saved = entityManager.merge(holding)
        entityManager.flush()
        return saved
    }

    override fun listForUser(discordId: Long, guildId: Long): List<UserCoinHoldingDto> {
        return entityManager.createQuery(
            "select h from UserCoinHoldingDto h " +
                    "where h.discordId = :discordId and h.guildId = :guildId and h.amount > 0",
            UserCoinHoldingDto::class.java
        ).setParameter("discordId", discordId)
            .setParameter("guildId", guildId)
            .resultList
    }

    override fun listForGuild(guildId: Long): List<UserCoinHoldingDto> {
        return entityManager.createQuery(
            "select h from UserCoinHoldingDto h where h.guildId = :guildId and h.amount > 0",
            UserCoinHoldingDto::class.java
        ).setParameter("guildId", guildId)
            .resultList
    }
}
