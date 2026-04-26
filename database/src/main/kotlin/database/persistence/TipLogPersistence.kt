package database.persistence

import database.dto.TipLogDto

interface TipLogPersistence {
    fun insert(row: TipLogDto): TipLogDto
}
