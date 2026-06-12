package database.service.economy

import database.dto.guild.ConfigDto
import database.dto.economy.TobyCoinMarketDto
import database.dto.economy.TobyCoinPricePointDto
import database.dto.economy.TobyCoinTradeDto
import database.dto.user.UserDto
import common.economy.Coin
import common.economy.TobyCoinEngine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import database.persistence.economy.UserCoinHoldingPersistence
import database.service.guild.ConfigService
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService

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
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val holdingPersistence: UserCoinHoldingPersistence,
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
            val fee: Long = 0L,
        ) : TradeOutcome

        data class InsufficientCredits(val needed: Long, val have: Long) : TradeOutcome
        data class InsufficientCoins(val needed: Long, val have: Long) : TradeOutcome
        data object InvalidAmount : TradeOutcome
        data object UnknownUser : TradeOutcome
    }

    fun loadOrCreateMarket(guildId: Long, coin: Coin = Coin.DEFAULT): TobyCoinMarketDto {
        val existing = marketService.getMarket(guildId, coin)
        if (existing != null) return existing
        val now = Instant.now()
        val fresh = TobyCoinMarketDto(
            guildId = guildId,
            coin = coin.symbol,
            price = coin.initialPrice,
            lastTickAt = now
        )
        marketService.saveMarket(fresh)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = guildId, coin = coin.symbol, sampledAt = now, price = fresh.price)
        )
        return fresh
    }

    fun buy(
        discordId: Long,
        guildId: Long,
        amount: Long,
        reason: String = REASON_USER,
        coin: Coin = Coin.DEFAULT,
    ): TradeOutcome {
        if (amount <= 0) return TradeOutcome.InvalidAmount
        // Lock order — user, then (for non-TOBY) the holding row, then the
        // market. The same order is used in sell() to avoid deadlock.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return TradeOutcome.UnknownUser
        val balance = lockBalance(user, coin)
        val market = loadOrCreateMarketForUpdate(guildId, coin)

        val prePrice = market.price
        val newPrice = TobyCoinEngine.applyBuyPressure(prePrice, amount, coin)
        val executionPrice = (prePrice + newPrice) / 2.0
        // Buyer pays the midpoint price plus the fee on top — the fee
        // lives in the jackpot pool, not on the user's coin allotment.
        val gross = ceil(executionPrice * amount).toLong()
        val fee = ceil(gross * buyFeeRate(guildId)).toLong()
        val cost = gross + fee
        val credits = user.socialCredit ?: 0L
        if (credits < cost) return TradeOutcome.InsufficientCredits(cost, credits)

        user.socialCredit = credits - cost
        val newCoins = balance.add(amount)
        userService.updateUser(user)

        if (fee > 0L) jackpotService.addToPool(guildId, fee)

        commitPriceChange(market, newPrice, executionPrice, discordId, "BUY", amount, reason, coin)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = cost,
            newCoins = newCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice,
            fee = fee,
        )
    }

    fun sell(
        discordId: Long,
        guildId: Long,
        amount: Long,
        reason: String = REASON_USER,
        coin: Coin = Coin.DEFAULT,
    ): TradeOutcome {
        if (amount <= 0) return TradeOutcome.InvalidAmount
        // Lock order — user, then (for non-TOBY) the holding row, then the
        // market. The same order is used in buy() to avoid deadlock.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return TradeOutcome.UnknownUser
        val balance = lockBalance(user, coin)
        val market = loadOrCreateMarketForUpdate(guildId, coin)

        if (balance.current < amount) return TradeOutcome.InsufficientCoins(amount, balance.current)

        val prePrice = market.price
        val newPrice = TobyCoinEngine.applySellPressure(prePrice, amount, coin)
        val executionPrice = (prePrice + newPrice) / 2.0
        // Seller's fee is taken off the midpoint proceeds before they
        // hit the wallet — the fee feeds the jackpot pool.
        val gross = floor(executionPrice * amount).toLong()
        val fee = floor(gross * sellFeeRate(guildId)).toLong()
        val proceeds = gross - fee
        val newCoins = balance.add(-amount)
        user.socialCredit = (user.socialCredit ?: 0L) + proceeds
        userService.updateUser(user)

        if (fee > 0L) jackpotService.addToPool(guildId, fee)

        commitPriceChange(market, newPrice, executionPrice, discordId, "SELL", amount, reason, coin)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = proceeds,
            newCoins = newCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice,
            fee = fee,
        )
    }

    /**
     * Abstracts where a coin's balance lives: TOBY in [UserDto.tobyCoins]
     * (so titles / casino / leaderboard keep settling in it), every other
     * coin in its own `user_coin_holding` row. For the non-TOBY case the
     * row is fetched under a pessimistic lock so concurrent trades of the
     * same coin serialise the same way TOBY trades do via the user lock.
     */
    private fun lockBalance(user: UserDto, coin: Coin): CoinBalance {
        if (coin == Coin.TOBY) {
            return object : CoinBalance {
                override val current: Long get() = user.tobyCoins
                override fun add(delta: Long): Long {
                    user.tobyCoins += delta
                    return user.tobyCoins
                }
            }
        }
        val holding = holdingPersistence.getForUpdateOrCreate(user.discordId, user.guildId, coin)
        return object : CoinBalance {
            override val current: Long get() = holding.amount
            override fun add(delta: Long): Long {
                holding.amount += delta
                holdingPersistence.save(holding)
                return holding.amount
            }
        }
    }

    private interface CoinBalance {
        val current: Long
        fun add(delta: Long): Long
    }

    /**
     * Resolve the per-guild buy fee rate from config, falling back to
     * [TobyCoinEngine.TRADE_FEE] (1 %) when unset, unparseable, NaN,
     * infinite, or negative. Clamps anything above
     * [TobyCoinEngine.MAX_TRADE_FEE] (25 %) to the cap. The stored
     * config value is a decimal percent (`1.0` means 1 %).
     */
    fun buyFeeRate(guildId: Long): Double =
        readFeeConfig(guildId, ConfigDto.Configurations.TRADE_BUY_FEE_PCT)

    /** Per-guild sell fee rate. See [buyFeeRate] — same semantics. */
    fun sellFeeRate(guildId: Long): Double =
        readFeeConfig(guildId, ConfigDto.Configurations.TRADE_SELL_FEE_PCT)

    private fun readFeeConfig(guildId: Long, key: ConfigDto.Configurations): Double {
        val cfg = configService.getConfigByName(key.configValue, guildId.toString())
        val pct = cfg?.value?.toDoubleOrNull() ?: return TobyCoinEngine.TRADE_FEE
        if (pct.isNaN() || pct.isInfinite() || pct < 0.0) return TobyCoinEngine.TRADE_FEE
        return (pct / 100.0).coerceAtMost(TobyCoinEngine.MAX_TRADE_FEE)
    }

    companion object {
        /** Manual `/economy` or `/tobycoin` trade — the default attribution. */
        const val REASON_USER = "USER"

        /** TitlesWebService.buyTitleWithTobyCoin auto-sold to cover a credit shortfall. */
        const val REASON_TITLE_TOPUP = "TITLE_TOPUP"

        /** A casino minigame auto-sold to fund a wager. */
        const val REASON_CASINO_TOPUP = "CASINO_TOPUP"
    }

    // Seed the market row if missing, then re-read it with a write lock. Only
    // the first trade for a brand-new guild hits the seed branch; after that
    // `getMarketForUpdate` finds the row immediately.
    private fun loadOrCreateMarketForUpdate(guildId: Long, coin: Coin): TobyCoinMarketDto {
        marketService.getMarketForUpdate(guildId, coin)?.let { return it }
        loadOrCreateMarket(guildId, coin)
        return marketService.getMarketForUpdate(guildId, coin)
            ?: error("Market row for guild $guildId / $coin could not be locked after creation")
    }

    private fun commitPriceChange(
        market: TobyCoinMarketDto,
        newPrice: Double,
        executionPrice: Double,
        discordId: Long,
        side: String,
        amount: Long,
        reason: String,
        coin: Coin,
    ) {
        val now = Instant.now()
        market.price = newPrice
        market.lastTickAt = now
        marketService.saveMarket(market)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = market.guildId, coin = coin.symbol, sampledAt = now, price = newPrice)
        )
        marketService.recordTrade(
            TobyCoinTradeDto(
                guildId = market.guildId,
                coin = coin.symbol,
                discordId = discordId,
                side = side,
                amount = amount,
                pricePerCoin = executionPrice,
                executedAt = now,
                reason = reason
            )
        )
    }
}
