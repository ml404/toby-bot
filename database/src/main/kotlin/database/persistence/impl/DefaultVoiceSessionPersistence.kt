package database.persistence.impl

import database.dto.VoiceSessionDto
import database.persistence.VoiceSessionPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional
class DefaultVoiceSessionPersistence : VoiceSessionPersistence {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun openSession(session: VoiceSessionDto): VoiceSessionDto {
        entityManager.persist(session)
        entityManager.flush()
        return session
    }

    override fun findOpenSession(discordId: Long, guildId: Long): VoiceSessionDto? {
        val q: TypedQuery<VoiceSessionDto> = entityManager.createNamedQuery(
            "VoiceSessionDto.findOpen",
            VoiceSessionDto::class.java
        )
        q.setParameter("discordId", discordId)
        q.setParameter("guildId", guildId)
        return q.resultList.firstOrNull()
    }

    override fun findAllOpenSessions(): List<VoiceSessionDto> {
        val q: TypedQuery<VoiceSessionDto> = entityManager.createNamedQuery(
            "VoiceSessionDto.findAllOpen",
            VoiceSessionDto::class.java
        )
        return q.resultList
    }

    override fun closeSession(session: VoiceSessionDto): VoiceSessionDto {
        entityManager.merge(session)
        entityManager.flush()
        return session
    }

    override fun sumCountedSecondsInRange(
        guildId: Long,
        discordId: Long,
        from: Instant,
        until: Instant
    ): Long {
        val q = entityManager.createNamedQuery("VoiceSessionDto.sumCountedSecondsInRange")
        q.setParameter("guildId", guildId)
        q.setParameter("discordId", discordId)
        q.setParameter("from", from)
        q.setParameter("until", until)
        return (q.singleResult as? Number)?.toLong() ?: 0L
    }

    @Suppress("UNCHECKED_CAST")
    override fun sumCountedSecondsInRangeByUser(
        guildId: Long,
        from: Instant,
        until: Instant
    ): Map<Long, Long> {
        val q = entityManager.createNamedQuery("VoiceSessionDto.sumCountedSecondsInRangeAllUsers")
        q.setParameter("guildId", guildId)
        q.setParameter("from", from)
        q.setParameter("until", until)
        val rows = q.resultList as List<Array<Any?>>
        return rows.associate {
            (it[0] as Number).toLong() to (it[1] as? Number)?.toLong().orZero()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun sumCountedSecondsLifetimeByUser(guildId: Long): Map<Long, Long> {
        val q = entityManager.createNamedQuery("VoiceSessionDto.sumCountedSecondsLifetime")
        q.setParameter("guildId", guildId)
        val rows = q.resultList as List<Array<Any?>>
        return rows.associate {
            (it[0] as Number).toLong() to (it[1] as? Number)?.toLong().orZero()
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
