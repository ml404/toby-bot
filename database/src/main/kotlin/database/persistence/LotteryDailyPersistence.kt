package database.persistence

import database.dto.LotteryDailyDto
import java.time.LocalDate

interface LotteryDailyPersistence {
    /** Returns the row for (guildId, drawDate) or null. Read-only — no lock. */
    fun get(guildId: Long, drawDate: LocalDate): LotteryDailyDto?

    /** Insert (idempotency marker). No-op if the row already exists. */
    fun upsert(row: LotteryDailyDto): LotteryDailyDto
}
