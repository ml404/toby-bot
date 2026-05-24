package database.persistence.guild

import database.dto.guild.TeamPresetDto

interface TeamPresetPersistence {
    fun listByGuild(guildId: Long): List<TeamPresetDto>
    fun getByGuildAndName(guildId: Long, name: String): TeamPresetDto?
    fun getById(id: Long): TeamPresetDto?
    fun save(dto: TeamPresetDto): TeamPresetDto
    fun update(dto: TeamPresetDto): TeamPresetDto
    fun deleteById(id: Long)
    fun deleteAllByGuild(guildId: Long)
}
