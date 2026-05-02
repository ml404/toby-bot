package database.persistence.impl

import database.dto.BlackjackHandLogDto
import database.persistence.BlackjackHandLogPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultBlackjackHandLogPersistence : BlackjackHandLogPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun insert(row: BlackjackHandLogDto): BlackjackHandLogDto {
        entityManager.persist(row)
        entityManager.flush()
        return row
    }

    override fun findRecentByTable(guildId: Long, tableId: Long, limit: Int): List<BlackjackHandLogDto> =
        entityManager.createQuery(
            "SELECT h FROM BlackjackHandLogDto h " +
                "WHERE h.guildId = :guildId AND h.tableId = :tableId " +
                "ORDER BY h.resolvedAt DESC, h.id DESC",
            BlackjackHandLogDto::class.java
        )
            .setParameter("guildId", guildId)
            .setParameter("tableId", tableId)
            .setMaxResults(limit.coerceAtLeast(0))
            .resultList

    override fun findRecentByGuild(guildId: Long, limit: Int): List<BlackjackHandLogDto> =
        entityManager.createQuery(
            "SELECT h FROM BlackjackHandLogDto h WHERE h.guildId = :guildId " +
                "ORDER BY h.resolvedAt DESC, h.id DESC",
            BlackjackHandLogDto::class.java
        )
            .setParameter("guildId", guildId)
            .setMaxResults(limit.coerceAtLeast(0))
            .resultList
}
