package web.service

import common.leveling.LevelCurve
import database.dto.UserDto
import database.service.AchievementService
import database.service.LoginStreakService
import database.service.TitleService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import web.util.GuildMembership
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class ProfileWebService(
    private val jda: JDA,
    private val userService: UserService,
    private val titleService: TitleService,
    private val introWebService: IntroWebService,
    private val membership: GuildMembership,
    private val loginStreakService: LoginStreakService,
    private val achievementService: AchievementService,
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

        val xp = user?.xp ?: 0L
        val progress = LevelCurve.progress(xp)

        val streakRow = loginStreakService.get(discordId, guildId)
        val today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC)
        val streakView = ProfileStreakView(
            currentStreak = streakRow?.currentStreak ?: 0,
            longestStreak = streakRow?.longestStreak ?: 0,
            lastClaimDate = streakRow?.lastClaimDate?.toString(),
            claimedToday = streakRow?.lastClaimDate == today,
        )

        val achievementViews = achievementService.listFor(discordId, guildId)
        val unlockedAchievements = achievementViews.filter { it.unlockedAt != null }
        val achievementEntries = achievementViews.map { v ->
            ProfileAchievementEntry(
                code = v.achievement.code,
                name = v.achievement.name,
                description = v.achievement.description,
                icon = v.achievement.icon,
                category = v.achievement.category,
                threshold = v.achievement.threshold,
                progress = v.progress,
                unlocked = v.unlockedAt != null,
            )
        }

        return ProfileView(
            guildId = guild.id,
            guildName = guild.name,
            displayName = member.effectiveName,
            avatarUrl = member.effectiveAvatarUrl,
            isOwner = member.isOwner,
            balance = user?.socialCredit ?: 0L,
            level = progress.level,
            xp = xp,
            xpIntoLevel = progress.xpIntoLevel,
            xpForNextLevel = progress.xpForNextLevel,
            xpProgressPercent = if (progress.xpForNextLevel > 0)
                ((progress.xpIntoLevel.toDouble() / progress.xpForNextLevel) * 100).toInt().coerceIn(0, 100)
            else 100,
            equippedTitleLabel = equippedTitle?.label,
            equippedTitleColorHex = equippedTitle?.colorHex,
            ownedTitles = ownedTitles,
            permissions = permissionsFor(user),
            streak = streakView,
            achievementsUnlocked = unlockedAchievements.size,
            achievementsTotal = achievementViews.size,
            achievements = achievementEntries,
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
    val level: Int,
    val xp: Long,
    val xpIntoLevel: Long,
    val xpForNextLevel: Long,
    val xpProgressPercent: Int,
    val equippedTitleLabel: String?,
    val equippedTitleColorHex: String?,
    val ownedTitles: List<ProfileTitleEntry>,
    val permissions: List<ProfilePermission>,
    val streak: ProfileStreakView,
    val achievementsUnlocked: Int,
    val achievementsTotal: Int,
    val achievements: List<ProfileAchievementEntry>
)

data class ProfileStreakView(
    val currentStreak: Int,
    val longestStreak: Int,
    val lastClaimDate: String?,
    val claimedToday: Boolean
)

data class ProfileAchievementEntry(
    val code: String,
    val name: String,
    val description: String,
    val icon: String?,
    val category: String,
    val threshold: Long,
    val progress: Long,
    val unlocked: Boolean
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
