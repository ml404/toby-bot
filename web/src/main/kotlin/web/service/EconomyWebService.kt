package web.service

import database.service.EconomyTradeService
import database.service.TobyCoinMarketService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class EconomyWebService(
    private val jda: JDA,
    private val introWebService: IntroWebService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val userService: UserService
) {

    fun isMember(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        return guild.getMemberById(discordId) != null
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
            portfolioCredits = ((user?.tobyCoins ?: 0L).toDouble() * market.price).toLong()
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
    val portfolioCredits: Long
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
