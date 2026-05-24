package database.persistence.economy

import database.dto.TobyCoinTradeDto
import java.time.Instant

interface TobyCoinTradePersistence {
    fun record(trade: TobyCoinTradeDto): TobyCoinTradeDto
    fun listSince(guildId: Long, since: Instant): List<TobyCoinTradeDto>
    fun deleteOlderThan(cutoff: Instant): Int
}
