package database.persistence

import database.dto.UserPriceTriggerDto

interface UserPriceTriggerPersistence {
    fun save(trigger: UserPriceTriggerDto): UserPriceTriggerDto

    fun findById(id: Long): UserPriceTriggerDto?

    fun listEnabledByGuild(guildId: Long): List<UserPriceTriggerDto>

    fun listByUser(discordId: Long, guildId: Long): List<UserPriceTriggerDto>

    fun deleteById(id: Long): Boolean
}
