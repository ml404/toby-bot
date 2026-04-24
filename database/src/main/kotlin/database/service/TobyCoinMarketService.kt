package database.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import java.time.Instant

interface TobyCoinMarketService {
    fun getMarket(guildId: Long): TobyCoinMarketDto?
    fun listMarkets(): List<TobyCoinMarketDto>
    fun saveMarket(market: TobyCoinMarketDto): TobyCoinMarketDto

    fun appendPricePoint(point: TobyCoinPricePointDto): TobyCoinPricePointDto
    fun listHistory(guildId: Long, since: Instant): List<TobyCoinPricePointDto>
    fun listAllHistory(guildId: Long): List<TobyCoinPricePointDto>
    fun pruneHistoryOlderThan(cutoff: Instant): Int
}
