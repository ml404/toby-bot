package database.persistence.economy

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto

interface TobyCoinMarketPersistence {
    fun getByGuild(guildId: Long, coin: Coin = Coin.DEFAULT): TobyCoinMarketDto?

    // SELECT ... FOR UPDATE. Only call inside an active @Transactional.
    fun getByGuildForUpdate(guildId: Long, coin: Coin = Coin.DEFAULT): TobyCoinMarketDto?

    fun listAll(): List<TobyCoinMarketDto>
    fun upsert(market: TobyCoinMarketDto): TobyCoinMarketDto
}
