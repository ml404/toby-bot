package database.service

import database.dto.VoiceCreditDailyDto
import java.time.LocalDate

interface VoiceCreditDailyService {
    fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto?
    fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto
}
