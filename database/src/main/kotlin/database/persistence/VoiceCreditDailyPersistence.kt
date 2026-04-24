package database.persistence

import database.dto.VoiceCreditDailyDto
import java.time.LocalDate

interface VoiceCreditDailyPersistence {
    fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto?
    fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto
}
