package database.persistence

import database.dto.TobyCoinJackpotWinnerDto

interface TobyCoinJackpotWinnerPersistence {
    fun get(guildId: Long, discordId: Long): TobyCoinJackpotWinnerDto?
    fun upsert(winner: TobyCoinJackpotWinnerDto): TobyCoinJackpotWinnerDto
}
