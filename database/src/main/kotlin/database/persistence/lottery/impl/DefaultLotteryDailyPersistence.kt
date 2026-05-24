package database.persistence.lottery.impl

import database.dto.LotteryDailyDto
import database.persistence.lottery.LotteryDailyPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
@Transactional
class DefaultLotteryDailyPersistence : LotteryDailyPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun get(guildId: Long, drawDate: LocalDate): LotteryDailyDto? {
        val q: TypedQuery<LotteryDailyDto> =
            entityManager.createNamedQuery("LotteryDailyDto.get", LotteryDailyDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("drawDate", drawDate)
        return q.resultList.firstOrNull()
    }

    override fun upsert(row: LotteryDailyDto): LotteryDailyDto {
        val existing = get(row.guildId, row.drawDate)
        return if (existing == null) {
            entityManager.persist(row)
            entityManager.flush()
            row
        } else {
            existing
        }
    }
}
