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
        val since = Instant.now().minus(Duration.ofDays(1))
        val dayAgo = marketService.listHistory(guildId, since).firstOrNull()?.price
        val change = dayAgo?.let { ((market.price - it) / it) * 100.0 }

        return EconomyView(
            guildName = guild.name,
            price = market.price,
            lastTickAt = market.lastTickAt,
            change24h = change,
            coins = user?.tobyCoins ?: 0L,
            credits = user?.socialCredit ?: 0L,
            portfolioCredits = ((user?.tobyCoins ?: 0L).toDouble() * market.price).toLong()
        )
    }

    fun getHistory(guildId: Long, window: String): List<PricePoint> {
        val now = Instant.now()
        val samples = when (window) {
            "5d" -> marketService.listHistory(guildId, now.minus(Duration.ofDays(5)))
            "1mo" -> marketService.listHistory(guildId, now.minus(Duration.ofDays(30)))
            "3mo" -> marketService.listHistory(guildId, now.minus(Duration.ofDays(90)))
            "1y" -> marketService.listHistory(guildId, now.minus(Duration.ofDays(365)))
            "all" -> marketService.listAllHistory(guildId)
            else -> marketService.listHistory(guildId, now.minus(Duration.ofDays(1)))
        }
        return samples.map { PricePoint(it.sampledAt.toEpochMilli(), it.price) }
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
    val change24h: Double?,
    val coins: Long,
    val credits: Long,
    val portfolioCredits: Long
)

data class PricePoint(
    val t: Long,
    val price: Double
)
