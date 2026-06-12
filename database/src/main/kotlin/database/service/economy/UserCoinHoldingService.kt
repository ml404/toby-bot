package database.service.economy

import common.economy.Coin
import database.dto.economy.UserCoinHoldingDto

/**
 * Read access to per-user balances of the NON-TOBY coins (TOBY stays in
 * `user.toby_coins`). The mutation path lives in [EconomyTradeService];
 * this exists so commands / pages can show a portfolio.
 */
interface UserCoinHoldingService {
    fun getAmount(discordId: Long, guildId: Long, coin: Coin): Long
    fun listForUser(discordId: Long, guildId: Long): List<UserCoinHoldingDto>

    /** Every non-zero holding in a guild — used to value all portfolios for the leaderboard. */
    fun listForGuild(guildId: Long): List<UserCoinHoldingDto>
}
