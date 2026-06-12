package database.persistence.economy

import common.economy.Coin
import database.dto.economy.UserCoinHoldingDto

interface UserCoinHoldingPersistence {
    /** Current balance, 0 if the user holds none of [coin]. */
    fun getAmount(discordId: Long, guildId: Long, coin: Coin): Long

    /**
     * Pessimistic-lock read of the holding row, creating a zero row if it
     * doesn't exist yet. Only call inside an active @Transactional — used
     * by the trade path so concurrent buys/sells of the same coin serialise.
     */
    fun getForUpdateOrCreate(discordId: Long, guildId: Long, coin: Coin): UserCoinHoldingDto

    fun save(holding: UserCoinHoldingDto): UserCoinHoldingDto

    /** All non-zero holdings a user has in a guild, for portfolio views. */
    fun listForUser(discordId: Long, guildId: Long): List<UserCoinHoldingDto>

    /** Every non-zero holding in a guild, for leaderboard aggregation. */
    fun listForGuild(guildId: Long): List<UserCoinHoldingDto>
}
