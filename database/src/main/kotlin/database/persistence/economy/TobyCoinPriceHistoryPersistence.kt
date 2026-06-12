package database.persistence.economy

import common.economy.Coin
import database.dto.economy.TobyCoinPricePointDto
import java.time.Instant

interface TobyCoinPriceHistoryPersistence {
    fun append(point: TobyCoinPricePointDto): TobyCoinPricePointDto
    fun listSince(guildId: Long, since: Instant, coin: Coin = Coin.DEFAULT): List<TobyCoinPricePointDto>
    fun listAll(guildId: Long, coin: Coin = Coin.DEFAULT): List<TobyCoinPricePointDto>
    fun deleteOlderThan(cutoff: Instant): Int
}
