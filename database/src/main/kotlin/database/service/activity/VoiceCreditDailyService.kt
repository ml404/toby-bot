package database.service.activity

import database.dto.activity.VoiceCreditDailyDto
import java.time.LocalDate

interface VoiceCreditDailyService {
    fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto?
    fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto
}
