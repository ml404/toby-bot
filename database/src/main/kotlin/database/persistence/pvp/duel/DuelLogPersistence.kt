package database.persistence.pvp.duel

import database.dto.DuelLogDto

interface DuelLogPersistence {
    fun insert(row: DuelLogDto): DuelLogDto
}
