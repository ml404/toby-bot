package web.service

import common.notification.NotificationChannelKind
import common.notification.Surface
import common.economy.Coin
import database.dto.economy.UserPriceTriggerDto
import database.dto.user.UserDto
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserCoinHoldingService
import database.service.user.UserNotificationPrefService
import database.service.economy.UserPriceTriggerService
import database.service.user.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

@Service
class EconomyWebService(
    private val jda: JDA,
    private val introWebService: IntroWebService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val userService: UserService,
    private val membership: GuildMembership,
    private val priceTriggerService: UserPriceTriggerService,
    private val notificationPrefService: UserNotificationPrefService,
    private val holdingService: UserCoinHoldingService,
) {

    companion object {
        // Same precision as PriceAlertCommand.PARITY_EPSILON. Reject
        // threshold == currentPrice (rounded to 4dp) so the firing
        // direction is unambiguous regardless of which way the next
        // tick moves. Copy-not-import to keep `web` from depending on
        // the `discord-bot` module.
        private const val PARITY_EPSILON = 1e-4
    }

    fun isMember(discordId: Long, guildId: Long): Boolean = membership.isMember(discordId, guildId)

    fun getGuildMembers(guildId: Long): List<MemberInfo> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        return guild.members
            .filter { !it.user.isBot }
            .map { MemberInfo(id = it.id, name = it.effectiveName, avatarUrl = it.effectiveAvatarUrl) }
            .sortedBy { it.name.lowercase() }
    }

    fun getGuildsWhereUserCanView(accessToken: String, discordId: Long): List<EconomyGuildCard> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            if (!isMember(discordId, guildId)) return@mapNotNull null
            val market = marketService.getMarket(guildId)
            val user = userService.getUserById(discordId, guildId)
            EconomyGuildCard(
                id = info.id,
                name = info.name,
                iconUrl = info.iconUrl,
                price = market?.price,
                coins = user?.tobyCoins ?: 0L,
                credits = user?.socialCredit ?: 0L
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Wallet balance for a single (user, guild) pair — the per-guild
     * credits the guild cards show, without the mutual-guild enumeration
     * of [getGuildsWhereUserCanView] (which needs an OAuth access token
     * that activity sessions don't carry).
     */
    fun getCredits(discordId: Long, guildId: Long): Long =
        userService.getUserById(discordId, guildId)?.socialCredit ?: 0L

    fun getEconomyView(guildId: Long, discordId: Long, coin: Coin = Coin.DEFAULT): EconomyView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val market = tradeService.loadOrCreateMarket(guildId, coin)
        val user = userService.getUserById(discordId, guildId)
        val coins = balanceOf(user, discordId, guildId, coin)

        return EconomyView(
            guildName = guild.name,
            coin = coin.symbol,
            coinName = coin.displayName,
            riskLabel = coin.riskLabel,
            // One tab per coin so the page can render the selector and link
            // to /economy/{guildId}?coin=SYMBOL for each.
            coinOptions = Coin.entries.map { c ->
                EconomyCoinTab(
                    symbol = c.symbol,
                    name = c.displayName,
                    riskLabel = c.riskLabel,
                    selected = c == coin,
                )
            },
            price = market.price,
            lastTickAt = market.lastTickAt,
            coins = coins,
            credits = user?.socialCredit ?: 0L,
            portfolioCredits = (coins.toDouble() * market.price).toLong(),
            // Raw rates (1 % == 0.01) so the JS preview math can use them
            // directly. Templates multiply by 100 for the percent label.
            buyFeeRate = tradeService.buyFeeRate(guildId),
            sellFeeRate = tradeService.sellFeeRate(guildId),
            // Per-coin trade impact so the client-side slippage preview
            // matches what the server applies for the wilder coins.
            tradeImpact = coin.tradeImpact
        )
    }

    /**
     * Where a coin's balance lives: TOBY in [UserDto.tobyCoins], every
     * other coin in `user_coin_holding`. Mirrors EconomyTradeService.
     */
    private fun balanceOf(user: UserDto?, discordId: Long, guildId: Long, coin: Coin): Long =
        if (coin == Coin.TOBY) user?.tobyCoins ?: 0L
        else holdingService.getAmount(discordId, guildId, coin)

    fun getHistory(guildId: Long, window: String, coin: Coin = Coin.DEFAULT): List<PricePoint> {
        val samples = when (val since = windowSince(window)) {
            null -> marketService.listAllHistory(guildId, coin)
            else -> marketService.listHistory(guildId, since, coin)
        }
        return samples.map { PricePoint(it.sampledAt.toEpochMilli(), it.price) }
    }

    fun getTrades(guildId: Long, window: String, coin: Coin = Coin.DEFAULT): List<TradeMarker> {
        // The "all" window has no lower bound for prices, but trades are
        // capped at 30 days by retention regardless. Pick a generous upper
        // bound so we don't pretend trades older than the prune cutoff
        // exist; "all" still returns whatever's left in the table.
        val since = windowSince(window) ?: Instant.now().minus(Duration.ofDays(365))
        val trades = marketService.listTradesSince(guildId, since, coin)
        if (trades.isEmpty()) return emptyList()

        val guild = jda.getGuildById(guildId)
        return trades.map { trade ->
            val name = guild?.getMemberById(trade.discordId)?.effectiveName ?: "Unknown"
            TradeMarker(
                t = trade.executedAt.toEpochMilli(),
                side = trade.side,
                amount = trade.amount,
                price = trade.pricePerCoin,
                name = name,
                reason = trade.reason
            )
        }
    }

    // Resolves a window code to a `since` Instant. Returns null for "all"
    // (callers should use the unbounded list).
    private fun windowSince(window: String): Instant? {
        val now = Instant.now()
        return when (window) {
            "5d" -> now.minus(Duration.ofDays(5))
            "1mo" -> now.minus(Duration.ofDays(30))
            "3mo" -> now.minus(Duration.ofDays(90))
            "1y" -> now.minus(Duration.ofDays(365))
            "all" -> null
            else -> now.minus(Duration.ofDays(1))
        }
    }

    fun buy(discordId: Long, guildId: Long, amount: Long, coin: Coin = Coin.DEFAULT): EconomyTradeService.TradeOutcome =
        tradeService.buy(discordId, guildId, amount, coin = coin)

    fun sell(discordId: Long, guildId: Long, amount: Long, coin: Coin = Coin.DEFAULT): EconomyTradeService.TradeOutcome =
        tradeService.sell(discordId, guildId, amount, coin = coin)

    fun listWatches(discordId: Long, guildId: Long, coin: Coin = Coin.DEFAULT): List<WatchView> =
        priceTriggerService.listForUser(discordId, guildId)
            .filter { it.coinEnum == coin }
            .map { it.toView() }

    fun createWatch(
        discordId: Long,
        guildId: Long,
        threshold: Double,
        side: UserPriceTriggerDto.Side,
        amount: Long,
        coin: Coin = Coin.DEFAULT,
    ): CreateWatchResult {
        if (amount <= 0) return CreateWatchResult.InvalidAmount

        val currentPrice = tradeService.loadOrCreateMarket(guildId, coin).price
        if (abs(threshold - currentPrice) < PARITY_EPSILON) {
            return CreateWatchResult.ParityRejected(threshold, currentPrice)
        }

        val created = priceTriggerService.create(
            discordId = discordId,
            guildId = guildId,
            threshold = threshold,
            priceAtCreation = currentPrice,
            side = side,
            amount = amount,
            coin = coin,
        )

        // Auto-enable PRICE_ALERT DM so the receipt is actually
        // delivered when the trigger fires — same convenience the
        // Discord command does (PriceAlertCommand.handleAdd).
        val wasOptedIn = notificationPrefService.isOptedIn(
            discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM
        )
        if (!wasOptedIn) {
            notificationPrefService.setPref(
                discordId, guildId,
                NotificationChannelKind.PRICE_ALERT, Surface.DM, optIn = true,
            )
        }

        return CreateWatchResult.Ok(created.toView(), notificationsAutoEnabled = !wasOptedIn)
    }

    fun removeWatch(id: Long, requestingDiscordId: Long): Boolean =
        priceTriggerService.remove(id, requestingDiscordId)

    fun currentPrice(guildId: Long, coin: Coin = Coin.DEFAULT): Double =
        tradeService.loadOrCreateMarket(guildId, coin).price

    /**
     * The signed-in user's whole portfolio in [guildId]: every coin they
     * hold (TOBY from the legacy column, the rest from `user_coin_holding`)
     * with its live value, plus their social-credit balance. `null` when
     * the bot isn't in the guild.
     */
    fun getPortfolio(guildId: Long, discordId: Long): PortfolioView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val user = userService.getUserById(discordId, guildId)
        val holdings = Coin.entries.mapNotNull { coin ->
            val amount = balanceOf(user, discordId, guildId, coin)
            if (amount <= 0L) return@mapNotNull null
            val price = marketService.getMarket(guildId, coin)?.price ?: coin.initialPrice
            PortfolioHolding(
                symbol = coin.symbol,
                name = coin.displayName,
                riskLabel = coin.riskLabel,
                amount = amount,
                price = price,
                value = (amount.toDouble() * price).toLong(),
            )
        }
        return PortfolioView(
            guildName = guild.name,
            credits = user?.socialCredit ?: 0L,
            holdings = holdings,
            totalCoinValue = holdings.sumOf { it.value },
        )
    }

    private fun UserPriceTriggerDto.toView() = WatchView(
        id = id ?: 0L,
        side = side,
        amount = amount,
        thresholdPrice = thresholdPrice,
        priceAtCreation = priceAtCreation,
        enabled = enabled,
        firedAt = firedAt,
        createdAt = createdAt,
    )
}

data class WatchView(
    val id: Long,
    val side: String,
    val amount: Long,
    val thresholdPrice: Double,
    val priceAtCreation: Double,
    val enabled: Boolean,
    val firedAt: Instant?,
    val createdAt: Instant,
)

sealed class CreateWatchResult {
    data class Ok(val watch: WatchView, val notificationsAutoEnabled: Boolean) : CreateWatchResult()
    data class ParityRejected(val threshold: Double, val currentPrice: Double) : CreateWatchResult()
    data object InvalidAmount : CreateWatchResult()
}

data class EconomyGuildCard(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val price: Double?,
    val coins: Long,
    val credits: Long
)

data class EconomyView(
    val guildName: String,
    val price: Double,
    val lastTickAt: Instant,
    val coins: Long,
    val credits: Long,
    val portfolioCredits: Long,
    /** Raw buy fee rate (1 % == 0.01) — used by the page's preview math. */
    val buyFeeRate: Double = 0.01,
    /** Raw sell fee rate. */
    val sellFeeRate: Double = 0.01,
    /** Per-coin trade impact so the page can compute slippage client-side. */
    val tradeImpact: Double = 0.0001,
    /** The coin this view is for — its ticker, e.g. "TOBY" / "MOON". */
    val coin: String = "TOBY",
    /** Human name of [coin], e.g. "Moonpup". */
    val coinName: String = "Toby Coin",
    /** Risk label of [coin], e.g. "High risk". */
    val riskLabel: String = "Baseline",
    /** Every coin, for rendering the selector tabs. */
    val coinOptions: List<EconomyCoinTab> = emptyList(),
)

data class EconomyCoinTab(
    val symbol: String,
    val name: String,
    val riskLabel: String,
    val selected: Boolean,
)

data class PortfolioView(
    val guildName: String,
    val credits: Long,
    val holdings: List<PortfolioHolding>,
    /** Sum of every holding's market value, in credits. */
    val totalCoinValue: Long,
)

data class PortfolioHolding(
    val symbol: String,
    val name: String,
    val riskLabel: String,
    val amount: Long,
    val price: Double,
    val value: Long,
)

data class PricePoint(
    val t: Long,
    val price: Double
)

data class TradeMarker(
    val t: Long,
    val side: String,
    val amount: Long,
    val price: Double,
    val name: String,
    /** USER / TITLE_TOPUP / CASINO_TOPUP — see TobyCoinTradeDto. */
    val reason: String = "USER"
)
