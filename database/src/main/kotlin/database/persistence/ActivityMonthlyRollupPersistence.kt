package database.persistence

import database.dto.ActivityMonthlyRollupDto
import java.time.LocalDate

interface ActivityMonthlyRollupPersistence {
    fun addSeconds(
        discordId: Long,
        guildId: Long,
        monthStart: LocalDate,
        activityName: String,
        delta: Long
    ): ActivityMonthlyRollupDto

    fun forGuildMonth(guildId: Long, monthStart: LocalDate): List<ActivityMonthlyRollupDto>
    fun forUser(guildId: Long, discordId: Long): List<ActivityMonthlyRollupDto>
    fun forUserMonth(guildId: Long, discordId: Long, monthStart: LocalDate): List<ActivityMonthlyRollupDto>
    fun deleteBefore(cutoff: LocalDate): Int
}
