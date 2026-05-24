package database.service.activity

import database.dto.activity.UbiDailyDto
import java.time.LocalDate

interface UbiDailyService {
    fun get(discordId: Long, guildId: Long, date: LocalDate): UbiDailyDto?
    fun upsert(row: UbiDailyDto): UbiDailyDto
    fun sumGrantedInRangeByUser(guildId: Long, from: LocalDate, until: LocalDate): Map<Long, Long>
}
