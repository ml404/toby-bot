package database.persistence

import database.dto.UbiDailyDto
import java.time.LocalDate

interface UbiDailyPersistence {
    fun get(discordId: Long, guildId: Long, date: LocalDate): UbiDailyDto?
    fun upsert(row: UbiDailyDto): UbiDailyDto
}
