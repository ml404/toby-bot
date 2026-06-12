package database.service.economy.impl

import common.economy.Coin
import database.dto.economy.UserCoinHoldingDto
import database.persistence.economy.UserCoinHoldingPersistence
import database.service.economy.UserCoinHoldingService
import org.springframework.stereotype.Service

@Service
class DefaultUserCoinHoldingService(
    private val persistence: UserCoinHoldingPersistence,
) : UserCoinHoldingService {

    override fun getAmount(discordId: Long, guildId: Long, coin: Coin): Long =
        persistence.getAmount(discordId, guildId, coin)

    override fun listForUser(discordId: Long, guildId: Long): List<UserCoinHoldingDto> =
        persistence.listForUser(discordId, guildId)

    override fun listForGuild(guildId: Long): List<UserCoinHoldingDto> =
        persistence.listForGuild(guildId)
}
