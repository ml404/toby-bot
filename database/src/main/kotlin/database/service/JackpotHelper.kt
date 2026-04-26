package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import kotlin.random.Random

/**
 * Two casino-side feeders for the per-guild jackpot pool:
 *
 *   - [rollOnWin] — on every minigame WIN, roll a small probability
 *     to bank the entire pool and reset it to zero.
 *   - [divertOnLoss] — on every minigame LOSS, deposit a fraction of
 *     the lost stake into the pool. The remaining fraction keeps
 *     vanishing as house edge; tribute is the visible chunk that
 *     funnels into the pool a future winner might claim. The fraction
 *     is per-guild configurable via the `JACKPOT_LOSS_TRIBUTE_PCT`
 *     config entry; defaults to [DEFAULT_LOSS_TRIBUTE] (10 %).
 *
 * Both paths go through [JackpotService] for the actual pool mutation
 * (pessimistic-lock per row), so concurrent winners can't double-bank
 * the same pool and concurrent losers can't lose deposits to last-
 * write-wins. Caller is expected to have already locked the user via
 * [WagerHelper.checkAndLock] — that lock implicitly serialises a
 * single user's casino actions.
 *
 * The third feeder is the trade fee in [EconomyTradeService] —
 * traders fund the pool, casino winners empty it, casino losers top
 * it up some more.
 */
internal object JackpotHelper {

    /** 1% chance to hit the jackpot per minigame win. Tuned alongside the trade fee. */
    const val WIN_PROBABILITY: Double = 0.01

    /**
     * Default fraction of a lost stake that feeds the per-guild
     * jackpot pool when the server hasn't overridden the rate via
     * the `JACKPOT_LOSS_TRIBUTE_PCT` config entry.
     */
    const val DEFAULT_LOSS_TRIBUTE: Double = 0.10

    /**
     * Hard cap on the configurable tribute. 50 % keeps the house edge
     * meaningful even with the most aggressive admin setting — without
     * it an admin could route every lost stake straight to the pool,
     * removing the gambling feedback loop entirely.
     */
    const val MAX_LOSS_TRIBUTE: Double = 0.50

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

    /**
     * On a Lose outcome, deposit `floor(stake × rate)` into the per-
     * guild pool, where `rate` comes from
     * [lossTributeRate] for [guildId]. The lost stake itself is
     * already deducted from the user's balance by
     * [WagerHelper.applyMultiplier] — this is a separate write into
     * the jackpot row, not a refund. Returns the amount deposited so
     * callers can surface it on the response (e.g. "+10 to jackpot"
     * on the lose line); `0L` if the floor produces nothing.
     */
    fun divertOnLoss(
        jackpotService: JackpotService,
        configService: ConfigService,
        guildId: Long,
        stake: Long
    ): Long {
        if (stake <= 0L) return 0L
        val rate = lossTributeRate(configService, guildId)
        if (rate <= 0.0) return 0L
        val tribute = kotlin.math.floor(stake * rate).toLong()
        if (tribute <= 0L) return 0L
        jackpotService.addToPool(guildId, tribute)
        return tribute
    }

    /**
     * Live tribute fraction for [guildId] — admin-set whole-number
     * percent (0-50) parsed from the `JACKPOT_LOSS_TRIBUTE_PCT` config
     * entry. Returns [DEFAULT_LOSS_TRIBUTE] when unset or unparseable;
     * clamps anything above [MAX_LOSS_TRIBUTE].
     */
    fun lossTributeRate(configService: ConfigService, guildId: Long): Double {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toDoubleOrNull() ?: return DEFAULT_LOSS_TRIBUTE
        return (pct / 100.0).coerceIn(0.0, MAX_LOSS_TRIBUTE)
    }
}
