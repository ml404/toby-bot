package database.persistence.social

import database.dto.ExcuseDto

interface ExcusePersistence {
    fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?>

    fun listApprovedPaged(guildId: Long?, offset: Int, limit: Int): List<ExcuseDto>
    fun listPendingPaged(guildId: Long?, offset: Int, limit: Int): List<ExcuseDto>
    fun searchApproved(guildId: Long?, query: String, offset: Int, limit: Int): List<ExcuseDto>

    fun countApproved(guildId: Long?): Long
    fun countPending(guildId: Long?): Long
    fun countSearchApproved(guildId: Long?, query: String): Long

    fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto?
    fun getExcuseById(id: Long?): ExcuseDto?
    fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto
    fun deleteAllExcusesForGuild(guildId: Long?)
    fun deleteExcuseById(id: Long?)
}
