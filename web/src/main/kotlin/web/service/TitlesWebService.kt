package web.service

import common.logging.DiscordLogger
import database.dto.TitleDto
import database.dto.UserOwnedTitleDto
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.ceil

@Service
class TitlesWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val titleService: TitleService,
    private val titleRoleService: TitleRoleService,
    private val introWebService: IntroWebService,
    private val marketService: TobyCoinMarketService,
    private val tradeService: EconomyTradeService
) {
    companion object {
        private val logger = DiscordLogger(TitlesWebService::class.java)
    }

    private fun <T> safely(label: String, default: T, block: () -> T): T =
        runCatching(block)
            .onFailure { logger.warn("Title shop enrichment failed ($label): ${it::class.simpleName}: ${it.message}") }
            .getOrDefault(default)

    fun getMemberGuilds(accessToken: String, discordId: Long): List<TitlesGuildCard> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            if (!isMember(discordId, guildId)) return@mapNotNull null
            val user = userService.getUserById(discordId, guildId)
            val equippedTitle = safely("equipped title for $discordId@$guildId", null as TitleDto?) {
                user?.activeTitleId?.let { titleService.getById(it) }
            }
            TitlesGuildCard(
                id = info.id,
                name = info.name,
                iconUrl = info.iconUrl,
                balance = user?.socialCredit ?: 0L,
                equippedTitle = equippedTitle?.label
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun isMember(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        return guild.getMemberById(discordId) != null
    }

    fun getTitlesForGuild(guildId: Long, actorDiscordId: Long): TitleShopView {
        val catalog = safely("title catalog", emptyList<TitleDto>()) {
            titleService.listAll()
        }.map { t ->
            TitleShopEntry(
                id = t.id ?: 0,
                label = t.label,
                cost = t.cost,
                description = t.description,
                colorHex = t.colorHex
            )
        }
        val ownedIds: Set<Long> = safely("owned titles", emptyList<UserOwnedTitleDto>()) {
            titleService.listOwned(actorDiscordId)
        }.mapTo(HashSet()) { it.titleId }
        val actorDto = userService.getUserById(actorDiscordId, guildId)
        val equippedId = actorDto?.activeTitleId
        val balance = actorDto?.socialCredit ?: 0L
        val tobyCoins = actorDto?.tobyCoins ?: 0L
        val marketPrice = safely("market price for $guildId", 0.0) {
            marketService.getMarket(guildId)?.price ?: 0.0
        }
        return TitleShopView(
            catalog = catalog,
            ownedTitleIds = ownedIds,
            equippedTitleId = equippedId,
            balance = balance,
            tobyCoins = tobyCoins,
            marketPrice = marketPrice
        )
    }

    fun buyTitle(actorDiscordId: Long, guildId: Long, titleId: Long): String? {
        if (jda.getGuildById(guildId) == null) return "Bot is not in that server."
        val title = titleService.getById(titleId) ?: return "Title not found."
        val actor = userService.getUserById(actorDiscordId, guildId) ?: return "You don't have a profile in that server yet."
        if (titleService.owns(actorDiscordId, titleId)) return "You already own this title."
        val balance = actor.socialCredit ?: 0L
        if (balance < title.cost) return "Not enough credits. You need ${title.cost}, you have $balance."
        actor.socialCredit = balance - title.cost
        userService.updateUser(actor)
        titleService.recordPurchase(actorDiscordId, titleId)
        return null
    }

    /**
     * One-click purchase that tops up the credit shortfall by selling TobyCoins
     * at the live market price. Runs in a single transaction so either the user
     * both sells TOBY and owns the title, or neither happens.
     */
    @Transactional
    fun buyTitleWithTobyCoin(actorDiscordId: Long, guildId: Long, titleId: Long): BuyWithTobyOutcome {
        if (jda.getGuildById(guildId) == null) {
            return BuyWithTobyOutcome.Error("Bot is not in that server.")
        }
        val title = titleService.getById(titleId) ?: return BuyWithTobyOutcome.Error("Title not found.")

        // Lock the user row first — same order as EconomyTradeService to avoid deadlock.
        val actor = userService.getUserByIdForUpdate(actorDiscordId, guildId)
            ?: return BuyWithTobyOutcome.Error("You don't have a profile in that server yet.")

        // Re-check ownership INSIDE the lock. Two concurrent callers can both
        // pre-check `owns=false`; if the check lived before the lock, the loser
        // would proceed past the gate and trip user_owned_title's PK on insert.
        // Inside the lock the loser observes the winner's commit and returns
        // AlreadyOwns cleanly.
        if (titleService.owns(actorDiscordId, titleId)) {
            return BuyWithTobyOutcome.AlreadyOwns
        }
        val currentCredits = actor.socialCredit ?: 0L
        val shortfall = (title.cost - currentCredits).coerceAtLeast(0L)

        var soldCoins = 0L
        var priceAfterSell = 0.0
        if (shortfall > 0L) {
            val market = marketService.getMarketForUpdate(guildId)
                ?: return BuyWithTobyOutcome.Error("No market yet for this server — try a Discord trade first.")
            if (market.price <= 0.0) {
                return BuyWithTobyOutcome.Error("Market price is not available right now.")
            }
            val coinsNeeded = ceil(shortfall.toDouble() / market.price).toLong()
            if (actor.tobyCoins < coinsNeeded) {
                return BuyWithTobyOutcome.InsufficientCoins(needed = coinsNeeded, have = actor.tobyCoins)
            }

            val sellOutcome = tradeService.sell(actorDiscordId, guildId, coinsNeeded)
            if (sellOutcome !is TradeOutcome.Ok) {
                return BuyWithTobyOutcome.Error("Sell failed: $sellOutcome")
            }
            soldCoins = coinsNeeded
            priceAfterSell = sellOutcome.newPrice
        } else {
            priceAfterSell = marketService.getMarket(guildId)?.price ?: 0.0
        }

        // Re-read under the same transaction; persistence context returns the
        // already-mutated managed entity after the nested sell.
        val refreshed = userService.getUserByIdForUpdate(actorDiscordId, guildId)
            ?: return BuyWithTobyOutcome.Error("User vanished mid-transaction.")
        refreshed.socialCredit = (refreshed.socialCredit ?: 0L) - title.cost
        userService.updateUser(refreshed)
        titleService.recordPurchase(actorDiscordId, titleId)

        return BuyWithTobyOutcome.Ok(
            soldTobyCoins = soldCoins,
            newCoins = refreshed.tobyCoins,
            newCredits = refreshed.socialCredit ?: 0L,
            newPrice = priceAfterSell
        )
    }

    fun equipTitle(actorDiscordId: Long, guildId: Long, titleId: Long): String? {
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val member = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val actor = userService.getUserById(actorDiscordId, guildId) ?: return "No profile in that server."
        val title = titleService.getById(titleId) ?: return "Title not found."
        if (!titleService.owns(actorDiscordId, titleId)) return "You don't own this title."
        val ownedIds = titleService.listOwned(actorDiscordId).map { it.titleId }.toSet()
        return when (val r = titleRoleService.equip(guild, member, title, ownedIds)) {
            is TitleRoleResult.Ok -> {
                actor.activeTitleId = titleId
                userService.updateUser(actor)
                null
            }
            is TitleRoleResult.Error -> r.message
        }
    }

    fun unequipTitle(actorDiscordId: Long, guildId: Long): String? {
        val guild = jda.getGuildById(guildId) ?: return "Bot is not in that server."
        val member = guild.getMemberById(actorDiscordId) ?: return "You are not a member of that server."
        val actor = userService.getUserById(actorDiscordId, guildId) ?: return "No profile in that server."
        val ownedIds = titleService.listOwned(actorDiscordId).map { it.titleId }.toSet()
        return when (val r = titleRoleService.unequip(guild, member, ownedIds)) {
            is TitleRoleResult.Ok -> {
                actor.activeTitleId = null
                userService.updateUser(actor)
                null
            }
            is TitleRoleResult.Error -> r.message
        }
    }
}

data class TitlesGuildCard(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val balance: Long,
    val equippedTitle: String?
)

data class TitleShopEntry(
    val id: Long,
    val label: String,
    val cost: Long,
    val description: String?,
    val colorHex: String?
)

data class TitleShopView(
    val catalog: List<TitleShopEntry>,
    val ownedTitleIds: Set<Long>,
    val equippedTitleId: Long?,
    val balance: Long,
    val tobyCoins: Long = 0L,
    val marketPrice: Double = 0.0
)

sealed interface BuyWithTobyOutcome {
    data class Ok(
        val soldTobyCoins: Long,
        val newCoins: Long,
        val newCredits: Long,
        val newPrice: Double
    ) : BuyWithTobyOutcome

    data class InsufficientCoins(val needed: Long, val have: Long) : BuyWithTobyOutcome
    data object AlreadyOwns : BuyWithTobyOutcome
    data class Error(val message: String) : BuyWithTobyOutcome
}
