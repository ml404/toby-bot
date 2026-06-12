package database.persistence.economy

import common.economy.Coin
import database.dto.economy.UserPriceTriggerDto

interface UserPriceTriggerPersistence {
    fun save(trigger: UserPriceTriggerDto): UserPriceTriggerDto

    fun findById(id: Long): UserPriceTriggerDto?

    fun listEnabledByGuildAndCoin(guildId: Long, coin: Coin): List<UserPriceTriggerDto>

    fun listByUser(discordId: Long, guildId: Long): List<UserPriceTriggerDto>

    fun deleteById(id: Long): Boolean
}
