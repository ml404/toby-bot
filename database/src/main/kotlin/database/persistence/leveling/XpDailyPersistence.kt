package database.persistence.leveling

import database.dto.leveling.XpDailyDto
import java.time.LocalDate

interface XpDailyPersistence {
    fun get(discordId: Long, guildId: Long, date: LocalDate): XpDailyDto?
    fun upsert(row: XpDailyDto): XpDailyDto
}
