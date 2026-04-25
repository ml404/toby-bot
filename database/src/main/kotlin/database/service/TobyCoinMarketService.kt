package database.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.TobyCoinTradeDto
import java.time.Instant

interface TobyCoinMarketService {
    fun getMarket(guildId: Long): TobyCoinMarketDto?

    // Non-cached pessimistic-lock read — must run inside @Transactional.
    fun getMarketForUpdate(guildId: Long): TobyCoinMarketDto?

    fun listMarkets(): List<TobyCoinMarketDto>
    fun saveMarket(market: TobyCoinMarketDto): TobyCoinMarketDto

    fun appendPricePoint(point: TobyCoinPricePointDto): TobyCoinPricePointDto
    fun listHistory(guildId: Long, since: Instant): List<TobyCoinPricePointDto>
    fun listAllHistory(guildId: Long): List<TobyCoinPricePointDto>
    fun pruneHistoryOlderThan(cutoff: Instant): Int

    fun recordTrade(trade: TobyCoinTradeDto): TobyCoinTradeDto
    fun listTradesSince(guildId: Long, since: Instant): List<TobyCoinTradeDto>
    fun pruneTradesOlderThan(cutoff: Instant): Int
}
