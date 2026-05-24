package database.service.leveling

import database.dto.leveling.XpDailyDto
import java.time.LocalDate

interface XpDailyService {
    fun get(discordId: Long, guildId: Long, date: LocalDate): XpDailyDto?
    fun upsert(row: XpDailyDto): XpDailyDto
}
