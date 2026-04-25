package database.service

import database.dto.UserDto
import kotlin.random.Random

/**
 * On every minigame win, roll a small probability of hitting the
 * per-guild jackpot pool. On a hit the player banks the entire pool
 * and the counter resets to zero — same lock-then-mutate path as the
 * other balance changes, threaded through the user row that
 * [WagerHelper.checkAndLock] already write-locked.
 *
 * Loses don't roll. The fee that funds the pool is paid on every
 * trade leg (see [EconomyTradeService]), so the "casino wins what
 * traders pay" loop is closed without slowing the trade path.
 */
internal object JackpotHelper {

    /** 1% chance to hit the jackpot per minigame win. Tuned alongside the trade fee. */
    const val WIN_PROBABILITY: Double = 0.01

    /**
     * If the random roll hits and the pool is non-empty, atomically
     * pull the entire pool, credit it to [user] (already locked by
     * [WagerHelper.checkAndLock]), persist, and return the amount
     * awarded. Returns `0L` on miss or empty pool.
     */
    fun rollOnWin(
        jackpotService: JackpotService,
        userService: UserService,
        user: UserDto,
        guildId: Long,
        random: Random
    ): Long {
        if (random.nextDouble() >= WIN_PROBABILITY) return 0L
        val won = jackpotService.awardJackpot(guildId)
        if (won == 0L) return 0L
        user.socialCredit = (user.socialCredit ?: 0L) + won
        userService.updateUser(user)
        return won
    }
}
