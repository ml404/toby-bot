package database.service.social

import database.dto.social.TipDailyDto
import java.time.LocalDate

interface TipDailyService {
    fun get(senderDiscordId: Long, guildId: Long, date: LocalDate): TipDailyDto?
    fun upsert(row: TipDailyDto): TipDailyDto
}
