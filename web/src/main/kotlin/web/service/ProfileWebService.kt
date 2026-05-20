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
        val totalXpEarned = unlockedAchievements.sumOf { it.achievement.xpReward.toLong() }
        val achievementCategories = buildAchievementCategories(achievementViews)

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
            totalXpEarned = totalXpEarned,
            achievementCategories = achievementCategories,
        )
    }

    private fun buildAchievementCategories(
        views: List<AchievementService.AchievementView>
    ): List<ProfileAchievementCategory> {
        if (views.isEmpty()) return emptyList()
        val grouped = views.groupBy { it.achievement.category }
        val ordered = CATEGORY_ORDER.mapNotNull { key ->
            grouped[key]?.let { key to it }
        } + grouped.entries
            .filter { it.key !in CATEGORY_ORDER }
            .sortedBy { it.key }
            .map { it.key to it.value }

        return ordered.map { (key, entries) ->
            val sorted = entries.sortedWith(
                compareBy<AchievementService.AchievementView> { it.unlockedAt == null }
                    .thenByDescending { it.unlockedAt?.epochSecond ?: 0L }
                    .thenByDescending { it.progress }
            )
            val display = CATEGORY_DISPLAY[key]
            ProfileAchievementCategory(
                key = key,
                label = display?.first ?: key.replaceFirstChar { it.uppercase() },
                icon = display?.second ?: "🏅",
                unlockedCount = sorted.count { it.unlockedAt != null },
                total = sorted.size,
                entries = sorted.map(::toEntry),
            )
        }
    }

    private fun toEntry(view: AchievementService.AchievementView): ProfileAchievementEntry {
        val a = view.achievement
        val percent = if (a.threshold > 0)
            ((view.progress.toDouble() / a.threshold) * 100).toInt().coerceIn(0, 100)
        else 0
        return ProfileAchievementEntry(
            code = a.code,
            name = a.name,
            description = a.description,
            icon = a.icon,
            category = a.category,
            threshold = a.threshold,
            progress = view.progress,
            progressPercent = percent,
            unlocked = view.unlockedAt != null,
            unlockedAt = view.unlockedAt?.toString(),
            xpReward = a.xpReward,
            creditReward = a.creditReward,
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

    companion object {
        private val CATEGORY_ORDER = listOf("streak", "level", "casino", "social", "music", "voice", "consolation")

        // Pair(label, icon)
        private val CATEGORY_DISPLAY = mapOf(
            "streak" to ("Streaks" to "🔥"),
            "level" to ("Levels" to "🎖️"),
            "casino" to ("Casino" to "🎰"),
            "social" to ("Social" to "🤝"),
            "music" to ("Music" to "🎵"),
            "voice" to ("Voice" to "🎙️"),
            "consolation" to ("Consolation" to "🩹"),
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
    val totalXpEarned: Long,
    val achievementCategories: List<ProfileAchievementCategory>,
)

data class ProfileStreakView(
    val currentStreak: Int,
    val longestStreak: Int,
    val lastClaimDate: String?,
    val claimedToday: Boolean
)

data class ProfileAchievementCategory(
    val key: String,
    val label: String,
    val icon: String,
    val unlockedCount: Int,
    val total: Int,
    val entries: List<ProfileAchievementEntry>,
)

data class ProfileAchievementEntry(
    val code: String,
    val name: String,
    val description: String,
    val icon: String?,
    val category: String,
    val threshold: Long,
    val progress: Long,
    val progressPercent: Int,
    val unlocked: Boolean,
    val unlockedAt: String?,
    val xpReward: Int,
    val creditReward: Long,
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
