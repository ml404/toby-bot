package database.service

import database.dto.ExcuseDto

interface ExcuseService {
    fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?>

    /** Paged variants. Page numbers are 1-based. */
    fun listApprovedPaged(guildId: Long?, page: Int, pageSize: Int): PagedExcuses
    fun listPendingPaged(guildId: Long?, page: Int, pageSize: Int): PagedExcuses
    fun searchApproved(guildId: Long?, query: String, page: Int, pageSize: Int): PagedExcuses

    fun countApproved(guildId: Long?): Long
    fun countPending(guildId: Long?): Long

    fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto?
    fun getExcuseById(id: Long?): ExcuseDto?
    fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto?

    /**
     * Approve a pending excuse. Returns the updated DTO, or null if no
     * excuse exists with [id], or the existing DTO if it was already
     * approved (caller can detect "no-op" by checking the returned
     * `approved` was already true before the call).
     */
    fun approveExcuse(id: Long): ExcuseDto?

    /**
     * Best-effort author check used by the self-delete-own-pending rule.
     * Returns true iff [requesterDiscordId] is recorded as the author of
     * the row referred to by [id] AND that row is still pending. Approved
     * rows always require superuser to delete.
     */
    fun canRequesterDeleteOwnPending(id: Long, requesterDiscordId: Long): Boolean

    fun deleteExcuseByGuildId(guildId: Long?)
    fun deleteExcuseById(id: Long?)
    fun clearCache()
}

/**
 * 1-based page envelope. [totalPages] is at least 1 even when there are no
 * rows so the renderer can show "Page 1 of 1" instead of "Page 1 of 0".
 */
data class PagedExcuses(
    val rows: List<ExcuseDto>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Long,
) {
    val totalPages: Int get() = if (totalCount == 0L) 1 else ((totalCount + pageSize - 1) / pageSize).toInt()
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page < totalPages
}
