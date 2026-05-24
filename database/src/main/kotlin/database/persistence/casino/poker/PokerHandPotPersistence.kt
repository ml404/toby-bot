package database.persistence.casino.poker

import database.dto.casino.poker.PokerHandPotDto

interface PokerHandPotPersistence {
    fun insert(row: PokerHandPotDto): PokerHandPotDto
    fun findByHandLogId(handLogId: Long): List<PokerHandPotDto>
}
