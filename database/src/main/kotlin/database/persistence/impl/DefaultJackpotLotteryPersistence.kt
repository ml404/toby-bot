package database.persistence.impl

import database.dto.JackpotLotteryDto
import database.dto.JackpotLotteryTicketDto
import database.dto.JackpotLotteryTicketId
import database.persistence.JackpotLotteryPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultJackpotLotteryPersistence : JackpotLotteryPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun getOpenByGuild(guildId: Long): JackpotLotteryDto? {
        val q: TypedQuery<JackpotLotteryDto> = entityManager.createNamedQuery(
            "JackpotLotteryDto.getOpenByGuild", JackpotLotteryDto::class.java
        )
        q.setParameter("guildId", guildId)
        return q.resultList.firstOrNull()
    }

    override fun getOpenByGuildForUpdate(guildId: Long): JackpotLotteryDto? {
        val q: TypedQuery<JackpotLotteryDto> = entityManager.createNamedQuery(
            "JackpotLotteryDto.getOpenByGuild", JackpotLotteryDto::class.java
        )
        q.setParameter("guildId", guildId)
        q.lockMode = LockModeType.PESSIMISTIC_WRITE
        return q.resultList.firstOrNull()
    }

    override fun upsert(lottery: JackpotLotteryDto): JackpotLotteryDto {
        val saved = if (lottery.id == null) {
            entityManager.persist(lottery)
            lottery
        } else {
            entityManager.merge(lottery)
        }
        entityManager.flush()
        return saved
    }

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
        val saved = if (existing == null) {
            entityManager.persist(ticket)
            ticket
        } else {
            entityManager.merge(ticket)
        }
        entityManager.flush()
        return saved
    }

    override fun ticketsByLottery(lotteryId: Long): List<JackpotLotteryTicketDto> {
        val q: TypedQuery<JackpotLotteryTicketDto> = entityManager.createNamedQuery(
            "JackpotLotteryTicketDto.byLottery", JackpotLotteryTicketDto::class.java
        )
        q.setParameter("lotteryId", lotteryId)
        return q.resultList
    }
}
