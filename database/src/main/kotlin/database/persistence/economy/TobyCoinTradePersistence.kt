package database.persistence.economy

import common.economy.Coin
import database.dto.economy.TobyCoinTradeDto
import java.time.Instant

interface TobyCoinTradePersistence {
    fun record(trade: TobyCoinTradeDto): TobyCoinTradeDto
    fun listSince(guildId: Long, since: Instant, coin: Coin = Coin.DEFAULT): List<TobyCoinTradeDto>
    fun deleteOlderThan(cutoff: Instant): Int
}
