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

    /**
     * Default fraction of a winning casino-game roll that triggers the
     * jackpot payout when the server hasn't overridden the rate via
     * the `JACKPOT_WIN_PCT` config entry. Tuned alongside the trade fee.
     */
    const val DEFAULT_WIN_PROBABILITY: Double = 0.01

    /**
     * Default share of the pool paid on a winning roll when the server
     * hasn't overridden the value via the `JACKPOT_PAYOUT_PCT` config
     * entry. 100 % preserves the historic behaviour (winner banks the
     * entire pool); guilds that have suffered a runaway pool can lower
     * this so the remainder re-seeds the next cycle.
     */
    const val DEFAULT_PAYOUT_PCT: Double = 1.0

    /**
     * Default cooldown in days during which a prior jackpot winner is
     * ineligible for another payout. 0 disables the gate (current
     * behaviour for unconfigured guilds); recommended value is 14.
     */
    const val DEFAULT_WINNER_COOLDOWN_DAYS: Long = 0L

    /**
     * Hard cap on the configurable cooldown. A year is plenty — anything
     * longer is effectively a permanent ban and should be surfaced as a
     * separate moderation action, not a "cooldown."
     */
    const val MAX_WINNER_COOLDOWN_DAYS: Long = 365L

    /**
     * Default trailing-day window over which the eligibility gate counts
     * the user's distinct activity days. 0 disables the gate (current
     * behaviour); recommended value is 7 paired with `MIN_ACTIVITY_DAYS=3`.
     */
    const val DEFAULT_ACTIVITY_WINDOW_DAYS: Long = 0L

    /** Hard cap on the activity window — a calendar year. */
    const val MAX_ACTIVITY_WINDOW_DAYS: Long = 365L

    /**
     * Default minimum count of distinct activity days within the window
     * required for jackpot eligibility. Ignored when the window is 0.
     */
    const val DEFAULT_ACTIVITY_MIN_DAYS: Long = 1L

    /**
     * Default reference stake size for jackpot probability scaling
     * when `JACKPOT_STAKE_ANCHOR` is unset. Bets at or above this value
     * roll at the full base probability; smaller bets scale linearly.
     * Matches the historical hardcoded MAX_STAKE on most minigames so
     * behaviour is unchanged out of the box.
     */
    const val DEFAULT_STAKE_ANCHOR: Long = 500L

    /**
     * Hard cap on the configurable win probability. 50 % keeps the
     * "growing pool" tension meaningful — without it an admin could
     * empty the pool on virtually every win.
     */
    const val MAX_WIN_PROBABILITY: Double = 0.50

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
     * awarded. Returns `0L` on miss or empty pool. The hit probability
     * is per-guild configurable via `JACKPOT_WIN_PCT`; defaults to
     * [DEFAULT_WIN_PROBABILITY].
     *
     * The base probability is scaled linearly by `stake / anchor`,
     * where `anchor` is the per-guild `JACKPOT_STAKE_ANCHOR` config
     * (default [DEFAULT_STAKE_ANCHOR]). A min-wager autoclicker can't
     * farm the pool — a 10-credit play against a 500 anchor rolls at
     * 0.02 % even when the configured base is 1 %. Bets at or above
     * the anchor cap at full base probability. Decoupled from each
     * game's max stake so admins can raise stakes arbitrarily without
     * shrinking jackpot odds.
     */
    fun rollOnWin(
        jackpotService: JackpotService,
        configService: ConfigService,
        userService: UserService,
        user: UserDto,
        guildId: Long,
        stake: Long,
        random: Random
    ): Long {
        val baseProbability = winProbability(configService, guildId)
        val anchor = stakeAnchor(configService, guildId)
        val scale = (stake.toDouble() / anchor.toDouble()).coerceIn(0.0, 1.0)
        val effective = baseProbability * scale
        if (random.nextDouble() >= effective) return 0L

        // Post-fraud structural gates. Each defaults to "disabled" so an
        // unconfigured guild's behaviour is unchanged. When enabled, a
        // failed gate looks identical to a missed roll — pool keeps
        // growing, no payout, no exception thrown into the wager service.
        if (jackpotService.isOnCooldown(guildId, user.discordId)) return 0L
        if (!jackpotService.isActive(guildId, user.discordId)) return 0L

        val won = jackpotService.awardJackpot(guildId)
        if (won == 0L) return 0L
        jackpotService.recordWin(guildId, user.discordId, won)
        user.socialCredit = (user.socialCredit ?: 0L) + won
        userService.updateUser(user)
        return won
    }

    /**
     * Live jackpot stake anchor for [guildId] — admin-set whole-number
     * credits parsed from the `JACKPOT_STAKE_ANCHOR` config entry.
     * Returns [DEFAULT_STAKE_ANCHOR] when unset or unparseable; coerces
     * `>= 1L` so a zero never reaches the divisor.
     */
    fun stakeAnchor(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
            guildId.toString()
        )
        return cfg?.value?.toLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_STAKE_ANCHOR
    }

    /**
     * Live win probability for [guildId] — admin-set decimal percent
     * (0-50, decimals allowed) parsed from the `JACKPOT_WIN_PCT` config
     * entry. Returns [DEFAULT_WIN_PROBABILITY] when unset, unparseable,
     * or negative; clamps anything above [MAX_WIN_PROBABILITY].
     */
    fun winProbability(configService: ConfigService, guildId: Long): Double {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toDoubleOrNull() ?: return DEFAULT_WIN_PROBABILITY
        if (pct.isNaN() || pct.isInfinite() || pct < 0.0) return DEFAULT_WIN_PROBABILITY
        return (pct / 100.0).coerceAtMost(MAX_WIN_PROBABILITY)
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

    /**
     * Live payout fraction for [guildId] — admin-set whole-number percent
     * (1-100) parsed from `JACKPOT_PAYOUT_PCT`. Returns [DEFAULT_PAYOUT_PCT]
     * (1.0 = full pool) when unset or unparseable; clamps to (0, 1].
     * 0 is treated as missing and falls back to the default to avoid
     * silently disabling all jackpot payouts via a fat-fingered config row.
     */
    fun payoutPct(configService: ConfigService, guildId: Long): Double {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toDoubleOrNull() ?: return DEFAULT_PAYOUT_PCT
        if (pct.isNaN() || pct.isInfinite() || pct <= 0.0) return DEFAULT_PAYOUT_PCT
        return (pct / 100.0).coerceIn(0.0, 1.0).let { if (it == 0.0) DEFAULT_PAYOUT_PCT else it }
    }

    /**
     * Live cooldown window for [guildId] in days — admin-set whole number
     * parsed from `JACKPOT_WINNER_COOLDOWN_DAYS`. Returns
     * [DEFAULT_WINNER_COOLDOWN_DAYS] (0 = disabled) when unset or
     * unparseable; clamps to `[0, MAX_WINNER_COOLDOWN_DAYS]`.
     */
    fun winnerCooldownDays(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
            guildId.toString()
        )
        val days = cfg?.value?.toLongOrNull() ?: return DEFAULT_WINNER_COOLDOWN_DAYS
        return days.coerceIn(0L, MAX_WINNER_COOLDOWN_DAYS)
    }

    /**
     * Live activity-eligibility window for [guildId] in days — admin-set
     * whole number parsed from `JACKPOT_ACTIVITY_WINDOW_DAYS`. Returns
     * [DEFAULT_ACTIVITY_WINDOW_DAYS] (0 = gate disabled) when unset or
     * unparseable; clamps to `[0, MAX_ACTIVITY_WINDOW_DAYS]`.
     */
    fun activityWindowDays(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS.configValue,
            guildId.toString()
        )
        val days = cfg?.value?.toLongOrNull() ?: return DEFAULT_ACTIVITY_WINDOW_DAYS
        return days.coerceIn(0L, MAX_ACTIVITY_WINDOW_DAYS)
    }

    /**
     * Live minimum-activity-day requirement for [guildId] — admin-set
     * whole number parsed from `JACKPOT_ACTIVITY_MIN_DAYS`. Returns
     * [DEFAULT_ACTIVITY_MIN_DAYS] when unset or unparseable; coerces to
     * `>= 1L`. Only consulted when the activity window is `> 0`.
     */
    fun activityMinDays(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS.configValue,
            guildId.toString()
        )
        val days = cfg?.value?.toLongOrNull() ?: return DEFAULT_ACTIVITY_MIN_DAYS
        return days.coerceAtLeast(1L)
    }
}
