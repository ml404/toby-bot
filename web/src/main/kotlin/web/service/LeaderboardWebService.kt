package web.service

import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

@Service
class LeaderboardWebService(
    private val jda: JDA,
    private val introWebService: IntroWebService,
    private val moderationWebService: ModerationWebService
) {

    fun getGuildsWhereUserCanView(accessToken: String, discordId: Long): List<LeaderboardGuildCard> {
        return introWebService.getMutualGuilds(accessToken).mapNotNull { info ->
            val guildId = info.id.toLongOrNull() ?: return@mapNotNull null
            if (!isMember(discordId, guildId)) return@mapNotNull null
            val leaderboard = moderationWebService.getLeaderboard(guildId)
            val top = leaderboard.firstOrNull()
            LeaderboardGuildCard(
                id = info.id,
                name = info.name,
                iconUrl = info.iconUrl,
                topName = top?.name,
                topTitle = top?.title,
                topCredits = top?.socialCredit ?: 0L,
                totalVoiceSeconds = leaderboard.sumOf { it.voiceSecondsThisMonth },
                memberCount = leaderboard.size
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun isMember(discordId: Long, guildId: Long): Boolean {
        val guild = jda.getGuildById(guildId) ?: return false
        return guild.getMemberById(discordId) != null
    }

    fun getGuildView(guildId: Long): LeaderboardGuildView? {
        val guild = jda.getGuildById(guildId) ?: return null
        val rows = moderationWebService.getLeaderboard(guildId)
        val totalCreditsThisMonth = rows.sumOf { it.creditsEarnedThisMonth }
        val totalVoiceThisMonth = rows.sumOf { it.voiceSecondsThisMonth }
        val mostActiveName = rows.maxByOrNull { it.voiceSecondsThisMonth }?.name

        return LeaderboardGuildView(
            guildName = guild.name,
            podium = rows.take(3),
            standings = rows.drop(3),
            totalCreditsThisMonth = totalCreditsThisMonth,
            totalVoiceThisMonth = totalVoiceThisMonth,
            mostActiveMember = mostActiveName,
            totalMembers = rows.size
        )
    }
}

data class LeaderboardGuildCard(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val topName: String?,
    val topTitle: String?,
    val topCredits: Long,
    val totalVoiceSeconds: Long,
    val memberCount: Int
) {
    val totalVoiceDisplay: String get() = formatDuration(totalVoiceSeconds)
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}

data class LeaderboardGuildView(
    val guildName: String,
    val podium: List<LeaderboardRow>,
    val standings: List<LeaderboardRow>,
    val totalCreditsThisMonth: Long,
    val totalVoiceThisMonth: Long,
    val mostActiveMember: String?,
    val totalMembers: Int
) {
    val totalVoiceThisMonthDisplay: String get() = formatDuration(totalVoiceThisMonth)
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
