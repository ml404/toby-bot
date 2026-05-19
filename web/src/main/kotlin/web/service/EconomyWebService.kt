package web.service

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.UserPriceTriggerDto
import database.economy.TobyCoinEngine
import database.service.EconomyTradeService
import database.service.TobyCoinMarketService
import database.service.UserNotificationPrefService
import database.service.UserPriceTriggerService
import database.service.UserService
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

    fun getEconomyView(guildId: Long, discordId: Long): EconomyView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val market = tradeService.loadOrCreateMarket(guildId)
        val user = userService.getUserById(discordId, guildId)

        return EconomyView(
            guildName = guild.name,
            price = market.price,
            lastTickAt = market.lastTickAt,
            coins = user?.tobyCoins ?: 0L,
            credits = user?.socialCredit ?: 0L,
            portfolioCredits = ((user?.tobyCoins ?: 0L).toDouble() * market.price).toLong(),
            // Raw rates (1 % == 0.01) so the JS preview math can use them
            // directly. Templates multiply by 100 for the percent label.
            buyFeeRate = tradeService.buyFeeRate(guildId),
            sellFeeRate = tradeService.sellFeeRate(guildId),
            tradeImpact = TobyCoinEngine.TRADE_IMPACT
        )
    }

    fun getHistory(guildId: Long, window: String): List<PricePoint> {
        val samples = when (val since = windowSince(window)) {
            null -> marketService.listAllHistory(guildId)
            else -> marketService.listHistory(guildId, since)
        }
        return samples.map { PricePoint(it.sampledAt.toEpochMilli(), it.price) }
    }

    fun getTrades(guildId: Long, window: String): List<TradeMarker> {
        // The "all" window has no lower bound for prices, but trades are
        // capped at 30 days by retention regardless. Pick a generous upper
        // bound so we don't pretend trades older than the prune cutoff
        // exist; "all" still returns whatever's left in the table.
        val since = windowSince(window) ?: Instant.now().minus(Duration.ofDays(365))
        val trades = marketService.listTradesSince(guildId, since)
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

    fun buy(discordId: Long, guildId: Long, amount: Long): EconomyTradeService.TradeOutcome =
        tradeService.buy(discordId, guildId, amount)

    fun sell(discordId: Long, guildId: Long, amount: Long): EconomyTradeService.TradeOutcome =
        tradeService.sell(discordId, guildId, amount)

    fun listWatches(discordId: Long, guildId: Long): List<WatchView> =
        priceTriggerService.listForUser(discordId, guildId).map { it.toView() }

    fun createWatch(
        discordId: Long,
        guildId: Long,
        threshold: Double,
        side: UserPriceTriggerDto.Side,
        amount: Long,
    ): CreateWatchResult {
        if (amount <= 0) return CreateWatchResult.InvalidAmount

        val currentPrice = tradeService.loadOrCreateMarket(guildId).price
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

    fun currentPrice(guildId: Long): Double = tradeService.loadOrCreateMarket(guildId).price

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
    /** [TobyCoinEngine.TRADE_IMPACT] copy so the page can compute slippage client-side. */
    val tradeImpact: Double = 0.0001
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
