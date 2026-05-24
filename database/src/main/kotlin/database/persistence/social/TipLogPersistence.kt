package database.persistence.social

import database.dto.social.TipLogDto

interface TipLogPersistence {
    fun insert(row: TipLogDto): TipLogDto
}
