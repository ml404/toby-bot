package database.service.social.impl

import database.dto.ExcuseDto
import database.service.social.ExcuseService
import database.service.social.PagedExcuses
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultExcuseService : ExcuseService {
    @Autowired
    private lateinit var excuseService: database.persistence.social.ExcusePersistence

    // The cache key prefixes guarantee a list lookup for guild A can't be served
    // from a slot last written for guild B. The previous implementation omitted
    // `key` entirely so all guilds collided on the same `excuses::<empty>` slot.
    @Cacheable(value = ["excuses"], key = "'all-' + #guildId")
    override fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listAllGuildExcuses(guildId)
    }

    @Cacheable(value = ["excuses"], key = "'approved-' + #guildId")
    override fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listApprovedGuildExcuses(guildId)
    }

    @Cacheable(value = ["excuses"], key = "'pending-' + #guildId")
    override fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listPendingGuildExcuses(guildId)
    }

    // Paged + search hit the DB every time — the per-page result sets are
    // ephemeral and not worth caching across requests.
    override fun listApprovedPaged(guildId: Long?, page: Int, pageSize: Int): PagedExcuses {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * pageSize
        return PagedExcuses(
            rows = excuseService.listApprovedPaged(guildId, offset, pageSize),
            page = safePage,
            pageSize = pageSize,
            totalCount = excuseService.countApproved(guildId),
        )
    }

    override fun listPendingPaged(guildId: Long?, page: Int, pageSize: Int): PagedExcuses {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * pageSize
        return PagedExcuses(
            rows = excuseService.listPendingPaged(guildId, offset, pageSize),
            page = safePage,
            pageSize = pageSize,
            totalCount = excuseService.countPending(guildId),
        )
    }

    override fun searchApproved(guildId: Long?, query: String, page: Int, pageSize: Int): PagedExcuses {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return PagedExcuses(emptyList(), 1, pageSize, 0L)
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * pageSize
        return PagedExcuses(
            rows = excuseService.searchApproved(guildId, trimmed, offset, pageSize),
            page = safePage,
            pageSize = pageSize,
            totalCount = excuseService.countSearchApproved(guildId, trimmed),
        )
    }

    override fun countApproved(guildId: Long?): Long = excuseService.countApproved(guildId)
    override fun countPending(guildId: Long?): Long = excuseService.countPending(guildId)

    // A create lands in pending; only the per-guild "all" and "pending" caches
    // need eviction. Approved-list cache stays valid until an approve runs.
    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto? {
        return excuseService.createNewExcuse(excuseDto)
    }

    override fun getExcuseById(id: Long?): ExcuseDto? {
        return excuseService.getExcuseById(id)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto? {
        return excuseService.updateExcuse(excuseDto)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun approveExcuse(id: Long): ExcuseDto? {
        val dto = excuseService.getExcuseById(id) ?: return null
        if (dto.approved) return dto
        dto.approved = true
        dto.approvedAt = Instant.now()
        return excuseService.updateExcuse(dto)
    }

    override fun canRequesterDeleteOwnPending(id: Long, requesterDiscordId: Long): Boolean {
        val dto = excuseService.getExcuseById(id) ?: return false
        if (dto.approved) return false
        return dto.authorDiscordId == requesterDiscordId
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun deleteExcuseByGuildId(guildId: Long?) {
        excuseService.deleteAllExcusesForGuild(guildId)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun deleteExcuseById(id: Long?) {
        excuseService.deleteExcuseById(id)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun clearCache() {
    }
}
