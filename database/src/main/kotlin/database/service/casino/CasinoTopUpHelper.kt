package database.service.casino

import database.dto.user.UserDto
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

/**
 * Shared sell-to-cover-shortfall helper for the casino minigame
 * services. Mirrors the shape of [TitlesWebService.buyTitleWithTobyCoin]:
 * size the sell to cover the shortfall under midpoint slippage + the
 * jackpot fee, validate the player holds enough TOBY, then delegate to
 * [EconomyTradeService.sell] with `reason="CASINO_TOPUP"` so the
 * resulting trade row shows up on the chart with the right attribution.
 *
 * Caller is expected to have already locked the user row via
 * [WagerHelper.checkAndLock]. The market row is locked here because
 * `EconomyTradeService.sell` re-locks it under the same transaction —
 * Hibernate returns the same managed entity, no second DB lock acquired.
 */
internal sealed interface TopUpResult {
    data class ToppedUp(
        val user: UserDto,
        val balance: Long,
        val soldCoins: Long,
        val newPrice: Double
    ) : TopUpResult

    data class InsufficientCoins(val needed: Long, val have: Long) : TopUpResult

    data object MarketUnavailable : TopUpResult
}

internal object CasinoTopUpHelper {

    /**
     * Lift [user]'s balance from [currentBalance] up to at least [stake] by
     * liquidating the player's coins via [EconomyTradeService.sellToCover]
     * — TOBY first, then the other coins. So a player who holds MOON/RUFF/
     * TOBL but not TOBY can still cover a wager. No-op responsibility lives
     * in the caller — only invoke this when balance < stake.
     *
     * Returns:
     *  - [TopUpResult.ToppedUp] when the shortfall was covered. `soldCoins`/
     *    `newPrice` carry the TOBY leg (0 / 0.0 when only other coins sold),
     *    so the existing "Sold N TOBY" receipt stays correct; the non-TOBY
     *    legs are reflected in the new balance.
     *  - [TopUpResult.InsufficientCoins] (`needed`/`have` in CREDITS) when
     *    selling everything still falls short of the stake.
     *  - [TopUpResult.MarketUnavailable] when there's nothing to liquidate
     *    (no coins, or no priced market for any held coin).
     */
    fun ensureCreditsForWager(
        tradeService: EconomyTradeService,
        marketService: TobyCoinMarketService,
        userService: UserService,
        user: UserDto,
        guildId: Long,
        currentBalance: Long,
        stake: Long
    ): TopUpResult {
        val shortfall = (stake - currentBalance).coerceAtLeast(0L)
        if (shortfall == 0L) {
            // Defensive — caller should have skipped the call entirely.
            return TopUpResult.ToppedUp(user, currentBalance, soldCoins = 0L, newPrice = 0.0)
        }

        val result = tradeService.sellToCover(
            discordId = user.discordId,
            guildId = guildId,
            shortfall = shortfall,
            feeRate = tradeService.sellFeeRate(guildId),
        )
        if (!result.covered) {
            // Nothing priced to sell at all → present it as the existing
            // "no market" path (which surfaces as plain insufficient
            // credits); otherwise the player has coins but not enough.
            return if (result.capacity <= 0L) TopUpResult.MarketUnavailable
            else TopUpResult.InsufficientCoins(needed = shortfall, have = result.capacity)
        }

        // Re-read the user under the same transaction so the wager sees the
        // post-sell balance — Hibernate returns the same managed entity.
        val refreshed = userService.getUserByIdForUpdate(user.discordId, guildId) ?: user
        return TopUpResult.ToppedUp(
            user = refreshed,
            balance = refreshed.socialCredit ?: 0L,
            soldCoins = result.tobyCoinsSold,
            newPrice = result.tobyNewPrice ?: 0.0
        )
    }
}
