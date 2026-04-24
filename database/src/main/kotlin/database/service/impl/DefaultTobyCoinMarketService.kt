package database.service.impl

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.persistence.TobyCoinMarketPersistence
import database.persistence.TobyCoinPriceHistoryPersistence
import database.service.TobyCoinMarketService
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

    @Cacheable(value = ["tobyCoinMarkets"], key = "#guildId")
    override fun getMarket(guildId: Long): TobyCoinMarketDto? {
        return marketPersistence.getByGuild(guildId)
    }

    // Bypass cache deliberately: the trade path needs a fresh DB row + row lock.
    override fun getMarketForUpdate(guildId: Long): TobyCoinMarketDto? {
        return marketPersistence.getByGuildForUpdate(guildId)
    }

    override fun listMarkets(): List<TobyCoinMarketDto> {
        return marketPersistence.listAll()
    }

    @CachePut(value = ["tobyCoinMarkets"], key = "#market.guildId")
    override fun saveMarket(market: TobyCoinMarketDto): TobyCoinMarketDto {
        return marketPersistence.upsert(market)
    }

    override fun appendPricePoint(point: TobyCoinPricePointDto): TobyCoinPricePointDto {
        return historyPersistence.append(point)
    }

    override fun listHistory(guildId: Long, since: Instant): List<TobyCoinPricePointDto> {
        return historyPersistence.listSince(guildId, since)
    }

    override fun listAllHistory(guildId: Long): List<TobyCoinPricePointDto> {
        return historyPersistence.listAll(guildId)
    }

    override fun pruneHistoryOlderThan(cutoff: Instant): Int {
        return historyPersistence.deleteOlderThan(cutoff)
    }
}
