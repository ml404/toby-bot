package database.persistence.lottery.impl

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.dto.JackpotLotteryTicketId
import database.persistence.lottery.JackpotLotteryPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import database.persistence.saveOrMerge

@Repository
@Transactional
class DefaultJackpotLotteryPersistence : JackpotLotteryPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getOpenByGuildAndMode(guildId: Long, mode: String): JackpotLotteryDto? {
        val q: TypedQuery<JackpotLotteryDto> = entityManager.createNamedQuery(
            "JackpotLotteryDto.getOpenByGuildAndMode", JackpotLotteryDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("mode", mode)
        return q.resultList.firstOrNull()
    }

    override fun getOpenByGuildAndModeForUpdate(guildId: Long, mode: String): JackpotLotteryDto? {
        val q: TypedQuery<JackpotLotteryDto> = entityManager.createNamedQuery(
            "JackpotLotteryDto.getOpenByGuildAndMode", JackpotLotteryDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("mode", mode)
        q.lockMode = LockModeType.PESSIMISTIC_WRITE
        return q.resultList.firstOrNull()
    }

    override fun getLatestByGuildAndMode(guildId: Long, mode: String): JackpotLotteryDto? {
        val q: TypedQuery<JackpotLotteryDto> = entityManager.createNamedQuery(
            "JackpotLotteryDto.getLatestByGuildAndMode", JackpotLotteryDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.setParameter("mode", mode)
        q.maxResults = 1
        return q.resultList.firstOrNull()
    }

    override fun upsert(lottery: JackpotLotteryDto): JackpotLotteryDto =
        entityManager.saveOrMerge(lottery, isNew = { it.id == null })

    override fun findById(lotteryId: Long): JackpotLotteryDto? =
        entityManager.find(JackpotLotteryDto::class.java, lotteryId)

    override fun getTicketForUpdate(lotteryId: Long, discordId: Long): JackpotLotteryTicketDto? {
        return entityManager.find(
            JackpotLotteryTicketDto::class.java,
            JackpotLotteryTicketId(lotteryId = lotteryId, discordId = discordId),
            LockModeType.PESSIMISTIC_WRITE,
        )
    }

    override fun upsertTicket(ticket: JackpotLotteryTicketDto): JackpotLotteryTicketDto {
        val existing = entityManager.find(
            JackpotLotteryTicketDto::class.java,
            JackpotLotteryTicketId(lotteryId = ticket.lotteryId, discordId = ticket.discordId)
        )
        return entityManager.saveOrMerge(ticket, isNew = { existing == null })
    }

    override fun ticketsByLottery(lotteryId: Long): List<JackpotLotteryTicketDto> {
        val q: TypedQuery<JackpotLotteryTicketDto> = entityManager.createNamedQuery(
            "JackpotLotteryTicketDto.byLottery", JackpotLotteryTicketDto::class.java
        )
        q.setParameter("lotteryId", lotteryId)
        return q.resultList
    }
}
