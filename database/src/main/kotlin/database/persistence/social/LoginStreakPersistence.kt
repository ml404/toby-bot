package database.persistence.social

import database.dto.social.LoginStreakDto
import java.time.LocalDate

interface LoginStreakPersistence {
    fun get(discordId: Long, guildId: Long): LoginStreakDto?
    fun upsert(row: LoginStreakDto): LoginStreakDto

    /**
     * Rows for [guildId] whose streak is [database.service.social.StreakStatus.AT_RISK]
     * on [today]: `current_streak > 0` and last claimed exactly yesterday,
     * so the streak is still alive and breaks at the next UTC midnight
     * unless claimed. Lapsed rows (last claim 2+ days ago) still carry
     * their stale `current_streak` and are excluded, because claiming
     * would restart them at 1 rather than save them. Used by
     * `StreakReminderJob` to DM the at-risk cohort. Empty when no rows match.
     */
    fun findActiveStreaksDueForReminder(guildId: Long, today: LocalDate): List<LoginStreakDto>
}
