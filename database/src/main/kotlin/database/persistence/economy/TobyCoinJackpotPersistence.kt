package database.persistence.economy

import database.dto.economy.TobyCoinJackpotDto

interface TobyCoinJackpotPersistence {
    fun getByGuild(guildId: Long): TobyCoinJackpotDto?

    /** SELECT … FOR UPDATE. Only call inside an active @Transactional. */
    fun getByGuildForUpdate(guildId: Long): TobyCoinJackpotDto?

    fun upsert(jackpot: TobyCoinJackpotDto): TobyCoinJackpotDto
}
