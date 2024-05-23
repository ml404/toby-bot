package toby.jpa.persistence

import toby.jpa.dto.ConfigDto

interface IConfigPersistence {
    fun listAllConfig(): List<ConfigDto?>?
    fun listGuildConfig(guildId: String?): List<ConfigDto?>?
    fun getConfigByName(name: String?, guildId: String?): ConfigDto?
    fun createNewConfig(configDto: ConfigDto): ConfigDto
    fun updateConfig(configDto: ConfigDto?): ConfigDto?
    fun deleteAll(guildId: String?)
    fun deleteConfig(guildId: String?, name: String?)
}
