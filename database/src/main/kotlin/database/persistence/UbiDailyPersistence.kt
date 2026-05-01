package database.persistence

import database.dto.UbiDailyDto
import java.time.LocalDate

interface UbiDailyPersistence {
    fun get(discordId: Long, guildId: Long, date: LocalDate): UbiDailyDto?
    fun upsert(row: UbiDailyDto): UbiDailyDto

    /** Sum credits granted in [from, until) per user. Used to exclude UBI from leaderboard deltas. */
    fun sumGrantedInRangeByUser(guildId: Long, from: LocalDate, until: LocalDate): Map<Long, Long>
}
