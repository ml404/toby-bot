package database.service

import database.dto.TobyCoinJackpotDto
import database.persistence.TobyCoinJackpotPersistence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Atomic operations on the per-guild jackpot pool.
 *
 * The pool is fed by a flat fee on every Toby Coin trade (see
 * [EconomyTradeService]). Casino minigame wins roll a small chance to
 * hit the jackpot — on a hit the player banks the entire pool and the
 * counter resets to zero. All mutations go through pessimistic row
 * locks so two concurrent winners can't both bank the same pool.
 */
@Service
@Transactional
class JackpotService(
    private val persistence: TobyCoinJackpotPersistence
) {

    /** Current pool size; 0 if no row yet for this guild. */
    fun getPool(guildId: Long): Long =
        persistence.getByGuild(guildId)?.pool ?: 0L

    /**
     * Add [amount] credits to the pool. Caller must already be inside a
     * @Transactional boundary; we lock the row to serialise concurrent
     * fee deposits. Negative or zero amounts are no-ops (so the trade
     * service doesn't need to special-case rounding-down to zero).
     */
    fun addToPool(guildId: Long, amount: Long): Long {
        if (amount <= 0L) return getPool(guildId)
        val row = lockOrCreate(guildId)
        row.pool += amount
        persistence.upsert(row)
        return row.pool
    }

    /**
     * Pay out the entire pool atomically. Returns the amount that was
     * in the pool at the moment of the lock; caller is responsible for
     * crediting that amount to the winner. Pool is set to zero in the
     * same transaction.
     */
    fun awardJackpot(guildId: Long): Long {
        val row = lockOrCreate(guildId)
        val won = row.pool
        if (won == 0L) return 0L
        row.pool = 0L
        persistence.upsert(row)
        return won
    }

    private fun lockOrCreate(guildId: Long): TobyCoinJackpotDto {
        persistence.getByGuildForUpdate(guildId)?.let { return it }
        // First fee for this guild — seed and re-read with the write lock.
        persistence.upsert(TobyCoinJackpotDto(guildId = guildId, pool = 0L))
        return persistence.getByGuildForUpdate(guildId)
            ?: error("Jackpot row for guild $guildId could not be locked after creation")
    }
}
