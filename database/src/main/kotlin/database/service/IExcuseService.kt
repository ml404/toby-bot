package database.service

import database.dto.ExcuseDto

interface IExcuseService {
    fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto?
    fun getExcuseById(id: Long?): ExcuseDto?
    fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto?
    fun deleteExcuseByGuildId(guildId: Long?)
    fun deleteExcuseById(id: Long?)
    fun clearCache()
}
