package database.persistence.impl

import database.dto.ExcuseDto
import jakarta.persistence.EntityManager
import jakarta.persistence.NoResultException
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultExcusePersistence internal constructor() : database.persistence.ExcusePersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.getAll", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.getApproved", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.getPending", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList
    }

    override fun listApprovedPaged(guildId: Long?, offset: Int, limit: Int): List<ExcuseDto> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.getApproved", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        q.firstResult = offset.coerceAtLeast(0)
        q.maxResults = limit.coerceAtLeast(1)
        return q.resultList
    }

    override fun listPendingPaged(guildId: Long?, offset: Int, limit: Int): List<ExcuseDto> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.getPending", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        q.firstResult = offset.coerceAtLeast(0)
        q.maxResults = limit.coerceAtLeast(1)
        return q.resultList
    }

    override fun searchApproved(guildId: Long?, query: String, offset: Int, limit: Int): List<ExcuseDto> {
        val q: TypedQuery<ExcuseDto> = entityManager.createNamedQuery("ExcuseDto.searchApproved", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        q.setParameter("q", query)
        q.firstResult = offset.coerceAtLeast(0)
        q.maxResults = limit.coerceAtLeast(1)
        return q.resultList
    }

    override fun countApproved(guildId: Long?): Long {
        val q = entityManager.createNamedQuery("ExcuseDto.countApproved", Long::class.javaObjectType)
        q.setParameter("guildId", guildId)
        return q.singleResult.toLong()
    }

    override fun countPending(guildId: Long?): Long {
        val q = entityManager.createNamedQuery("ExcuseDto.countPending", Long::class.javaObjectType)
        q.setParameter("guildId", guildId)
        return q.singleResult.toLong()
    }

    override fun countSearchApproved(guildId: Long?, query: String): Long {
        // No dedicated countQuery — for an in-memory page we'd usually run a parallel
        // COUNT, but the search result set is bounded by guild and approval already.
        // List the matching ids without fetching the full rows and count those.
        val q = entityManager.createQuery(
            "select count(e) from ExcuseDto e where e.guildId = :guildId and e.approved = true " +
                "and lower(e.excuse) like lower(concat('%', :q, '%'))",
            Long::class.javaObjectType
        )
        q.setParameter("guildId", guildId)
        q.setParameter("q", query)
        return q.singleResult.toLong()
    }

    override fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto? {
        return persistExcuseDto(excuseDto)
    }

    override fun getExcuseById(id: Long?): ExcuseDto? {
        if (id == null) return null
        val excuseQuery: TypedQuery<ExcuseDto> =
            entityManager.createNamedQuery("ExcuseDto.getById", ExcuseDto::class.java)
        excuseQuery.setParameter("id", id)
        return try {
            excuseQuery.singleResult
        } catch (_: NoResultException) {
            null
        }
    }

    override fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto {
        val dbExcuse = getExcuseById(excuseDto.id)

        // Hibernate merge copies every field from the detached entity, including
        // nulls — so a caller that builds a fresh ExcuseDto with only the
        // editable fields populated would nullify created_at and trip the
        // NOT NULL constraint. Preserve the immutable audit fields from the
        // loaded row when the caller hasn't supplied them.
        if (dbExcuse != null) {
            if (excuseDto.createdAt == null) excuseDto.createdAt = dbExcuse.createdAt
            if (excuseDto.authorDiscordId == null) excuseDto.authorDiscordId = dbExcuse.authorDiscordId
        }

        if (excuseDto != dbExcuse) {
            entityManager.merge(excuseDto)
            entityManager.flush()
        }

        return excuseDto
    }

    override fun deleteAllExcusesForGuild(guildId: Long?) {
        val excuseQuery = entityManager.createNamedQuery("ExcuseDto.deleteAllByGuildId")
        excuseQuery.setParameter("guildId", guildId)
        excuseQuery.executeUpdate()
    }

    override fun deleteExcuseById(id: Long?) {
        val excuseQuery = entityManager.createNamedQuery("ExcuseDto.deleteById")
        excuseQuery.setParameter("id", id)
        excuseQuery.executeUpdate()
    }

    private fun persistExcuseDto(excuseDto: ExcuseDto?): ExcuseDto? {
        // @Transactional on a private method is a no-op — Spring's proxy
        // can't intercept private calls. The class-level @Transactional
        // already wraps the public callers.
        entityManager.persist(excuseDto)
        entityManager.flush()
        return excuseDto
    }
}
