package toby.jpa.persistence

import toby.jpa.dto.ExcuseDto

interface IExcusePersistence {
    fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?>
    fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto?
    fun getExcuseById(id: Int?): ExcuseDto
    fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto
    fun deleteAllExcusesForGuild(guildId: Long?)
    fun deleteExcuseById(id: Int?)
}
