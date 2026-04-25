package database.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
import database.dto.TobyCoinTradeDto
import database.dto.UserDto
import database.economy.TobyCoinEngine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Shared trade path for the Toby Coin economy. Both the Discord
 * `/tobycoin` command and the web `/economy` page call through here so
 * the debit/credit maths and price-pressure application only live in
 * one place.
 *
 * The service is intentionally thin: it owns the atomic mutation of
 * user balances + market price + history append, and returns a
 * descriptive result so the caller can render messaging in whatever
 * form suits (Discord embed, web JSON, etc.).
 *
 * Pricing: trades execute at the **midpoint** of the pre-pressure and
 * post-pressure prices, i.e. the average price the trader's order
 * walks across as it consumes liquidity. Without this — when buys paid
 * the pre-pressure price and sells received the post-pressure price —
 * a buy-then-immediate-sell of N coins printed `TRADE_IMPACT × N² × P`
 * credits per round-trip (with N=1000 / P=100 / k=0.0001 that's 10k
 * credits a cycle, the live exploit this service was patched to
 * close). Midpoint slips the trader by half the impact on each leg,
 * which is what real markets approximate via "average fill price"
 * anyway, and it makes round-trips a strict net loss.
 *
 * Fees: a flat [TobyCoinEngine.TRADE_FEE] is skimmed off each leg and
 * routed into the per-guild jackpot pool ([JackpotService]). The
 * pool is paid out to casino minigame winners that hit the jackpot
 * roll, so trade activity directly seeds gambling rewards.
 */
@Service
@Transactional
class EconomyTradeService(
    private val userService: UserService,
    private val marketService: TobyCoinMarketService,
    private val jackpotService: JackpotService
) {

    sealed interface TradeOutcome {
        data class Ok(
            val amount: Long,
            val transactedCredits: Long,
            val newCoins: Long,
            val newCredits: Long,
            val newPrice: Double,
            // Fee skimmed off this trade and routed to the jackpot pool.
            // Defaulted so existing test fixtures keep compiling.
            val fee: Long = 0L
        ) : TradeOutcome

        data class InsufficientCredits(val needed: Long, val have: Long) : TradeOutcome
        data class InsufficientCoins(val needed: Long, val have: Long) : TradeOutcome
        data object InvalidAmount : TradeOutcome
        data object UnknownUser : TradeOutcome
    }

    fun loadOrCreateMarket(guildId: Long): TobyCoinMarketDto {
        val existing = marketService.getMarket(guildId)
        if (existing != null) return existing
        val now = Instant.now()
        val fresh = TobyCoinMarketDto(
            guildId = guildId,
            price = TobyCoinEngine.INITIAL_PRICE,
            lastTickAt = now
        )
        marketService.saveMarket(fresh)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = guildId, sampledAt = now, price = fresh.price)
        )
        return fresh
    }

    fun buy(discordId: Long, guildId: Long, amount: Long): TradeOutcome {
        if (amount <= 0) return TradeOutcome.InvalidAmount
        // Lock order — user first, then market. Same in sell() to avoid deadlock.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return TradeOutcome.UnknownUser
        val market = loadOrCreateMarketForUpdate(guildId)

        val prePrice = market.price
        val newPrice = TobyCoinEngine.applyBuyPressure(prePrice, amount)
        val executionPrice = (prePrice + newPrice) / 2.0
        // Buyer pays the midpoint price plus the fee on top — the fee
        // lives in the jackpot pool, not on the user's coin allotment.
        val gross = ceil(executionPrice * amount).toLong()
        val fee = ceil(gross * TobyCoinEngine.TRADE_FEE).toLong()
        val cost = gross + fee
        val credits = user.socialCredit ?: 0L
        if (credits < cost) return TradeOutcome.InsufficientCredits(cost, credits)

        user.socialCredit = credits - cost
        user.tobyCoins += amount
        userService.updateUser(user)

        if (fee > 0L) jackpotService.addToPool(guildId, fee)

        commitPriceChange(market, newPrice, executionPrice, discordId, "BUY", amount)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = cost,
            newCoins = user.tobyCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice,
            fee = fee
        )
    }

    fun sell(discordId: Long, guildId: Long, amount: Long): TradeOutcome {
        if (amount <= 0) return TradeOutcome.InvalidAmount
        // Lock order — user first, then market. Same in buy() to avoid deadlock.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return TradeOutcome.UnknownUser
        val market = loadOrCreateMarketForUpdate(guildId)

        if (user.tobyCoins < amount) return TradeOutcome.InsufficientCoins(amount, user.tobyCoins)

        val prePrice = market.price
        val newPrice = TobyCoinEngine.applySellPressure(prePrice, amount)
        val executionPrice = (prePrice + newPrice) / 2.0
        // Seller's fee is taken off the midpoint proceeds before they
        // hit the wallet — the fee feeds the jackpot pool.
        val gross = floor(executionPrice * amount).toLong()
        val fee = floor(gross * TobyCoinEngine.TRADE_FEE).toLong()
        val proceeds = gross - fee
        user.tobyCoins -= amount
        user.socialCredit = (user.socialCredit ?: 0L) + proceeds
        userService.updateUser(user)

        if (fee > 0L) jackpotService.addToPool(guildId, fee)

        commitPriceChange(market, newPrice, executionPrice, discordId, "SELL", amount)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = proceeds,
            newCoins = user.tobyCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice,
            fee = fee
        )
    }

    // Seed the market row if missing, then re-read it with a write lock. Only
    // the first trade for a brand-new guild hits the seed branch; after that
    // `getMarketForUpdate` finds the row immediately.
    private fun loadOrCreateMarketForUpdate(guildId: Long): TobyCoinMarketDto {
        marketService.getMarketForUpdate(guildId)?.let { return it }
        loadOrCreateMarket(guildId)
        return marketService.getMarketForUpdate(guildId)
            ?: error("Market row for guild $guildId could not be locked after creation")
    }

    private fun commitPriceChange(
        market: TobyCoinMarketDto,
        newPrice: Double,
        executionPrice: Double,
        discordId: Long,
        side: String,
        amount: Long
    ) {
        val now = Instant.now()
        market.price = newPrice
        market.lastTickAt = now
        marketService.saveMarket(market)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = market.guildId, sampledAt = now, price = newPrice)
        )
        marketService.recordTrade(
            TobyCoinTradeDto(
                guildId = market.guildId,
                discordId = discordId,
                side = side,
                amount = amount,
                pricePerCoin = executionPrice,
                executedAt = now
            )
        )
    }
}
