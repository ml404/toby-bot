package database.service

import database.dto.UserDto
import database.economy.TobyCoinEngine
import database.service.EconomyTradeService.TradeOutcome

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
     * Sell exactly enough TOBY to lift [user]'s balance from
     * [currentBalance] up to at least [stake], using the live market
     * price. No-op responsibility lives in the caller — only invoke
     * this when balance < stake.
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

        val market = marketService.getMarketForUpdate(guildId)
            ?: return TopUpResult.MarketUnavailable
        if (market.price <= 0.0) return TopUpResult.MarketUnavailable

        val coinsNeeded = TobyCoinEngine.coinsNeededForShortfall(
            shortfall, market.price, feeRate = tradeService.sellFeeRate(guildId)
        )
        if (user.tobyCoins < coinsNeeded) {
            return TopUpResult.InsufficientCoins(needed = coinsNeeded, have = user.tobyCoins)
        }

        val sell = tradeService.sell(
            discordId = user.discordId,
            guildId = guildId,
            amount = coinsNeeded,
            reason = EconomyTradeService.REASON_CASINO_TOPUP
        )
        if (sell !is TradeOutcome.Ok) {
            // Sell shouldn't realistically fail here — we already checked
            // coin count and market price. But surface as MarketUnavailable
            // rather than crashing the wager.
            return TopUpResult.MarketUnavailable
        }

        // Re-read the user under the same transaction so the wager sees
        // the post-sell balance — Hibernate returns the same managed
        // entity, no extra round-trip.
        val refreshed = userService.getUserByIdForUpdate(user.discordId, guildId) ?: user
        return TopUpResult.ToppedUp(
            user = refreshed,
            balance = refreshed.socialCredit ?: 0L,
            soldCoins = coinsNeeded,
            newPrice = sell.newPrice
        )
    }
}
