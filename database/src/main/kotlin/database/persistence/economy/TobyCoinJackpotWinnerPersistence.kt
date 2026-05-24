package database.persistence.economy

import database.dto.economy.TobyCoinJackpotWinnerDto

interface TobyCoinJackpotWinnerPersistence {
    fun get(guildId: Long, discordId: Long): TobyCoinJackpotWinnerDto?
    fun upsert(winner: TobyCoinJackpotWinnerDto): TobyCoinJackpotWinnerDto
}
