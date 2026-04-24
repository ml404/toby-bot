package database.service

import database.dto.TobyCoinMarketDto
import database.dto.TobyCoinPricePointDto
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
 */
@Service
@Transactional
class EconomyTradeService(
    private val userService: UserService,
    private val marketService: TobyCoinMarketService
) {

    sealed interface TradeOutcome {
        data class Ok(
            val amount: Long,
            val transactedCredits: Long,
            val newCoins: Long,
            val newCredits: Long,
            val newPrice: Double
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

        val cost = ceil(market.price * amount).toLong()
        val credits = user.socialCredit ?: 0L
        if (credits < cost) return TradeOutcome.InsufficientCredits(cost, credits)

        user.socialCredit = credits - cost
        user.tobyCoins += amount
        userService.updateUser(user)

        val newPrice = TobyCoinEngine.applyBuyPressure(market.price, amount)
        commitPriceChange(market, newPrice)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = cost,
            newCoins = user.tobyCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice
        )
    }

    fun sell(discordId: Long, guildId: Long, amount: Long): TradeOutcome {
        if (amount <= 0) return TradeOutcome.InvalidAmount
        // Lock order — user first, then market. Same in buy() to avoid deadlock.
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return TradeOutcome.UnknownUser
        val market = loadOrCreateMarketForUpdate(guildId)

        if (user.tobyCoins < amount) return TradeOutcome.InsufficientCoins(amount, user.tobyCoins)

        val proceeds = floor(market.price * amount).toLong()
        user.tobyCoins -= amount
        user.socialCredit = (user.socialCredit ?: 0L) + proceeds
        userService.updateUser(user)

        val newPrice = TobyCoinEngine.applySellPressure(market.price, amount)
        commitPriceChange(market, newPrice)

        return TradeOutcome.Ok(
            amount = amount,
            transactedCredits = proceeds,
            newCoins = user.tobyCoins,
            newCredits = user.socialCredit ?: 0L,
            newPrice = newPrice
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

    private fun commitPriceChange(market: TobyCoinMarketDto, newPrice: Double) {
        val now = Instant.now()
        market.price = newPrice
        market.lastTickAt = now
        marketService.saveMarket(market)
        marketService.appendPricePoint(
            TobyCoinPricePointDto(guildId = market.guildId, sampledAt = now, price = newPrice)
        )
    }
}
