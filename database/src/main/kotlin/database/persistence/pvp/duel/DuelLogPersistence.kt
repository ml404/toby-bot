package database.persistence.pvp.duel

import database.dto.pvp.duel.DuelLogDto

interface DuelLogPersistence {
    fun insert(row: DuelLogDto): DuelLogDto
}
