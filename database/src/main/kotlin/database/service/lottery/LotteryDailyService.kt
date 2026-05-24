package database.service.lottery

import database.dto.LotteryDailyDto
import database.persistence.lottery.LotteryDailyPersistence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Idempotency ledger for the daily match-numbers lottery auto-draw.
 *
 * Mirrors the [UbiDailyService] pattern: the scheduler asks
 * [alreadyRan] before doing any work for a guild on a given date, then
 * calls [markRan] once the open/draw work is committed. A row in the
 * underlying ledger means "today's cycle for this guild is done";
 * subsequent same-day invocations short-circuit.
 */
@Service
@Transactional
class LotteryDailyService(
    private val persistence: LotteryDailyPersistence,
) {

    /** Has the daily lottery cycle already run for [guildId] on [drawDate]? */
    fun alreadyRan(guildId: Long, drawDate: LocalDate): Boolean =
        persistence.get(guildId, drawDate) != null

    /**
     * Record that the daily cycle ran for [guildId] on [drawDate]. Safe
     * to call repeatedly — duplicate inserts are no-ops because the
     * persistence layer treats the composite key as upsert-or-skip.
     */
    fun markRan(guildId: Long, drawDate: LocalDate) {
        persistence.upsert(LotteryDailyDto(guildId = guildId, drawDate = drawDate))
    }
}
