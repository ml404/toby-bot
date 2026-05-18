package database.persistence

import database.dto.LoginStreakDto
import java.time.LocalDate

interface LoginStreakPersistence {
    fun get(discordId: Long, guildId: Long): LoginStreakDto?
    fun upsert(row: LoginStreakDto): LoginStreakDto

    /**
     * Rows for [guildId] where the user has an active streak
     * (`current_streak > 0`) AND `last_claim_date < today` (i.e. they
     * haven't claimed yet today and their streak will reset tomorrow
     * if they don't). Used by `StreakReminderJob` to DM the at-risk
     * cohort. Empty when no rows match.
     */
    fun findActiveStreaksDueForReminder(guildId: Long, today: LocalDate): List<LoginStreakDto>
}
