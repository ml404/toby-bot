package database.persistence

import database.dto.ExcuseDto

interface ExcusePersistence {
    fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto?
    fun getExcuseById(id: Long?): ExcuseDto
    fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto
    fun deleteAllExcusesForGuild(guildId: Long?)
    fun deleteExcuseById(id: Long?)
}
