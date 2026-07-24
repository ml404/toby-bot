package database.persistence.lottery.impl

import database.dto.lottery.LotteryStreakDto
import database.dto.lottery.LotteryStreakId
import database.persistence.lottery.LotteryStreakPersistence
import database.persistence.saveOrMerge
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultLotteryStreakPersistence : LotteryStreakPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getForUpdate(guildId: Long, discordId: Long): LotteryStreakDto? =
        entityManager.find(
            LotteryStreakDto::class.java,
            LotteryStreakId(guildId = guildId, discordId = discordId),
            LockModeType.PESSIMISTIC_WRITE,
        )

    override fun upsert(streak: LotteryStreakDto): LotteryStreakDto {
        val existing = entityManager.find(
            LotteryStreakDto::class.java,
            LotteryStreakId(guildId = streak.guildId, discordId = streak.discordId)
        )
        return entityManager.saveOrMerge(streak, isNew = { existing == null })
    }
}
