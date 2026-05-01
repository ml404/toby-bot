package web.service

import database.dto.UserDto
import database.service.TitleService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership

@Service
class ProfileWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val titleService: TitleService,
    private val introWebService: IntroWebService,
    private val membership: GuildMembership,
) {

    fun getMemberGuilds(accessToken: String, discordId: Long): List<ProfileGuildCard> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            val guild = jda.getGuildById(guildId) ?: return@mapNotNull null
            if (guild.getMemberById(discordId) == null) return@mapNotNull null
            val user = userService.getUserById(discordId, guildId)
            ProfileGuildCard(
                id = info.id,
                name = info.name,
                iconUrl = info.iconUrl,
                balance = user?.socialCredit ?: 0L
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun isMember(discordId: Long, guildId: Long): Boolean = membership.isMember(discordId, guildId)

    fun getProfile(discordId: Long, guildId: Long): ProfileView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val member = guild.getMemberById(discordId) ?: return null
        val user = userService.getUserById(discordId, guildId)
        val equippedTitle = user?.activeTitleId?.let { titleService.getById(it) }
        val ownedTitles = titleService.listOwned(discordId)
            .mapNotNull { owned -> titleService.getById(owned.titleId) }
            .map {
                ProfileTitleEntry(
                    id = it.id ?: 0L,
                    label = it.label,
                    cost = it.cost,
                    description = it.description,
                    colorHex = it.colorHex,
                    equipped = it.id == equippedTitle?.id
                )
            }
            .sortedBy { it.label.lowercase() }

        return ProfileView(
            guildId = guild.id,
            guildName = guild.name,
            displayName = member.effectiveName,
            avatarUrl = member.effectiveAvatarUrl,
            isOwner = member.isOwner,
            balance = user?.socialCredit ?: 0L,
            equippedTitleLabel = equippedTitle?.label,
            equippedTitleColorHex = equippedTitle?.colorHex,
            ownedTitles = ownedTitles,
            permissions = permissionsFor(user)
        )
    }

    private fun permissionsFor(user: UserDto?): List<ProfilePermission> {
        return listOf(
            ProfilePermission("Music", user?.musicPermission ?: true),
            ProfilePermission("Meme", user?.memePermission ?: true),
            ProfilePermission("Dig", user?.digPermission ?: true),
            ProfilePermission("Superuser", user?.superUser ?: false)
        )
    }
}

data class ProfileGuildCard(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val balance: Long
)

data class ProfileView(
    val guildId: String,
    val guildName: String,
    val displayName: String,
    val avatarUrl: String?,
    val isOwner: Boolean,
    val balance: Long,
    val equippedTitleLabel: String?,
    val equippedTitleColorHex: String?,
    val ownedTitles: List<ProfileTitleEntry>,
    val permissions: List<ProfilePermission>
)

data class ProfileTitleEntry(
    val id: Long,
    val label: String,
    val cost: Long,
    val description: String?,
    val colorHex: String?,
    val equipped: Boolean
)

data class ProfilePermission(
    val name: String,
    val enabled: Boolean
)
