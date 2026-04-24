package web.service

import common.logging.DiscordLogger
import database.dto.TitleDto
import database.dto.UserOwnedTitleDto
import database.service.TitleService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

@Service
class TitlesWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val titleService: TitleService,
    private val titleRoleService: TitleRoleService,
    private val introWebService: IntroWebService
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
        return TitleShopView(
            catalog = catalog,
            ownedTitleIds = ownedIds,
            equippedTitleId = equippedId,
            balance = balance
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
    val balance: Long
)
