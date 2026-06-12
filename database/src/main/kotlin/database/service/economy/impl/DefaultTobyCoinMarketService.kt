package database.service.economy.impl

import common.economy.Coin
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinPricePointDto
import database.dto.economy.TobyCoinTradeDto
import database.persistence.economy.TobyCoinMarketPersistence
import database.persistence.economy.TobyCoinPriceHistoryPersistence
import database.persistence.economy.TobyCoinTradePersistence
import database.service.economy.TobyCoinMarketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DefaultTobyCoinMarketService : TobyCoinMarketService {
    @Autowired
    private lateinit var marketPersistence: TobyCoinMarketPersistence

    @Autowired
    private lateinit var historyPersistence: TobyCoinPriceHistoryPersistence

    @Autowired
    private lateinit var tradePersistence: TobyCoinTradePersistence

    // Cache key carries the coin so different coins for one guild don't
    // collide. '#coin.name' keeps the key a stable String.
    @Cacheable(value = ["tobyCoinMarkets"], key = "#guildId + ':' + #coin.name")
    override fun getMarket(guildId: Long, coin: Coin): TobyCoinMarketDto? {
        return marketPersistence.getByGuild(guildId, coin)
    }

    // Bypass cache deliberately: the trade path needs a fresh DB row + row lock.
    override fun getMarketForUpdate(guildId: Long, coin: Coin): TobyCoinMarketDto? {
        return marketPersistence.getByGuildForUpdate(guildId, coin)
    }

    override fun listMarkets(): List<TobyCoinMarketDto> {
        return marketPersistence.listAll()
    }

    @CachePut(value = ["tobyCoinMarkets"], key = "#market.guildId + ':' + #market.coin")
    override fun saveMarket(market: TobyCoinMarketDto): TobyCoinMarketDto {
        return marketPersistence.upsert(market)
    }

    override fun appendPricePoint(point: TobyCoinPricePointDto): TobyCoinPricePointDto {
        return historyPersistence.append(point)
    }

    override fun listHistory(guildId: Long, since: Instant, coin: Coin): List<TobyCoinPricePointDto> {
        return historyPersistence.listSince(guildId, since, coin)
    }

    override fun listAllHistory(guildId: Long, coin: Coin): List<TobyCoinPricePointDto> {
        return historyPersistence.listAll(guildId, coin)
    }

    override fun pruneHistoryOlderThan(cutoff: Instant): Int {
        return historyPersistence.deleteOlderThan(cutoff)
    }

    override fun recordTrade(trade: TobyCoinTradeDto): TobyCoinTradeDto {
        return tradePersistence.record(trade)
    }

    override fun listTradesSince(guildId: Long, since: Instant, coin: Coin): List<TobyCoinTradeDto> {
        return tradePersistence.listSince(guildId, since, coin)
    }

    override fun pruneTradesOlderThan(cutoff: Instant): Int {
        return tradePersistence.deleteOlderThan(cutoff)
    }
}
