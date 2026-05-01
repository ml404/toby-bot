package database.persistence

import database.dto.PokerHandPotDto

interface PokerHandPotPersistence {
    fun insert(row: PokerHandPotDto): PokerHandPotDto
    fun findByHandLogId(handLogId: Long): List<PokerHandPotDto>
}
