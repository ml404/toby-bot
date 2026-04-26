package database.persistence

import database.dto.PokerHandLogDto

interface PokerHandLogPersistence {
    fun insert(row: PokerHandLogDto): PokerHandLogDto
}
