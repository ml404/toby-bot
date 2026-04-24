package database.persistence

import database.dto.TobyCoinMarketDto

interface TobyCoinMarketPersistence {
    fun getByGuild(guildId: Long): TobyCoinMarketDto?

    // SELECT ... FOR UPDATE. Only call inside an active @Transactional.
    fun getByGuildForUpdate(guildId: Long): TobyCoinMarketDto?

    fun listAll(): List<TobyCoinMarketDto>
    fun upsert(market: TobyCoinMarketDto): TobyCoinMarketDto
}
