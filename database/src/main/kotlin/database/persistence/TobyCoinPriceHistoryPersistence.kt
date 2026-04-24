package database.persistence

import database.dto.TobyCoinPricePointDto
import java.time.Instant

interface TobyCoinPriceHistoryPersistence {
    fun append(point: TobyCoinPricePointDto): TobyCoinPricePointDto
    fun listSince(guildId: Long, since: Instant): List<TobyCoinPricePointDto>
    fun listAll(guildId: Long): List<TobyCoinPricePointDto>
    fun deleteOlderThan(cutoff: Instant): Int
}
