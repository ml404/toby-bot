package database.service.economy

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinPricePointDto
import database.dto.economy.TobyCoinTradeDto
import java.time.Instant

interface TobyCoinMarketService {
    fun getMarket(guildId: Long, coin: Coin = Coin.DEFAULT): TobyCoinMarketDto?

    // Non-cached pessimistic-lock read — must run inside @Transactional.
    fun getMarketForUpdate(guildId: Long, coin: Coin = Coin.DEFAULT): TobyCoinMarketDto?

    fun listMarkets(): List<TobyCoinMarketDto>
    fun saveMarket(market: TobyCoinMarketDto): TobyCoinMarketDto

    fun appendPricePoint(point: TobyCoinPricePointDto): TobyCoinPricePointDto
    fun listHistory(guildId: Long, since: Instant, coin: Coin = Coin.DEFAULT): List<TobyCoinPricePointDto>
    fun listAllHistory(guildId: Long, coin: Coin = Coin.DEFAULT): List<TobyCoinPricePointDto>
    fun pruneHistoryOlderThan(cutoff: Instant): Int

    fun recordTrade(trade: TobyCoinTradeDto): TobyCoinTradeDto
    fun listTradesSince(guildId: Long, since: Instant, coin: Coin = Coin.DEFAULT): List<TobyCoinTradeDto>
    fun pruneTradesOlderThan(cutoff: Instant): Int
}
