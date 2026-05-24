package database.persistence.activity

import database.dto.activity.VoiceCreditDailyDto
import java.time.LocalDate

interface VoiceCreditDailyPersistence {
    fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto?
    fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto

    /**
     * Count distinct activity days for [discordId] in [guildId] on or after
     * [from] where the user actually earned (credits > 0). Used by the
     * jackpot eligibility gate to require a minimum recent-engagement
     * footprint before paying out a roll.
     */
    fun countDaysSince(discordId: Long, guildId: Long, from: LocalDate): Long
}
