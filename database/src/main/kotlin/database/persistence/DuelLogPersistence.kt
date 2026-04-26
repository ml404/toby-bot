package database.persistence

import database.dto.DuelLogDto

interface DuelLogPersistence {
    fun insert(row: DuelLogDto): DuelLogDto
}
