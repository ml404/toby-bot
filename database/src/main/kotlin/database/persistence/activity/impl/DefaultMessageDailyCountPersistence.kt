package database.persistence.activity.impl

import database.dto.activity.MessageDailyCountDto
import database.persistence.activity.MessageDailyCountPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import database.persistence.saveOrMerge

@Repository
@Transactional
class DefaultMessageDailyCountPersistence : MessageDailyCountPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun findByGuildSince(guildId: Long, since: LocalDate): List<MessageDailyCountDto> {
        val query: TypedQuery<MessageDailyCountDto> =
            entityManager.createNamedQuery("MessageDailyCountDto.findByGuildSince", MessageDailyCountDto::class.java)
        query.setParameter("guildId", guildId)
        query.setParameter("since", since)
        return query.resultList
    }

    override fun increment(guildId: Long, dayStart: LocalDate, delta: Long) {
        if (delta <= 0L) return
        val existing = findByGuildAndDay(guildId, dayStart)
        if (existing != null) {
            existing.count += delta
            existing.updatedAt = Instant.now()
            entityManager.saveOrMerge(existing, isNew = { false })
        } else {
            entityManager.saveOrMerge(
                MessageDailyCountDto(
                    guildId = guildId,
                    dayStart = dayStart,
                    count = delta,
                    updatedAt = Instant.now(),
                ),
                isNew = { true },
            )
        }
    }

    private fun findByGuildAndDay(guildId: Long, dayStart: LocalDate): MessageDailyCountDto? {
        val query: TypedQuery<MessageDailyCountDto> =
            entityManager.createNamedQuery("MessageDailyCountDto.findByGuildAndDay", MessageDailyCountDto::class.java)
        query.setParameter("guildId", guildId)
        query.setParameter("dayStart", dayStart)
        return query.resultList.firstOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    override fun findLastActiveByGuild(): Map<Long, LocalDate> {
        val rows = entityManager
            .createNamedQuery("MessageDailyCountDto.lastActiveByGuild")
            .resultList as List<Array<Any?>>
        return rows.mapNotNull { row ->
            val guildId = (row[0] as? Number)?.toLong() ?: return@mapNotNull null
            val day = row[1] as? LocalDate ?: return@mapNotNull null
            guildId to day
        }.toMap()
    }
}
