package database.service.economy

import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import common.casino.JackpotWheel
import kotlin.random.Random
import database.service.guild.ConfigService
import database.service.economy.JackpotService
import database.service.user.UserService

/**
 * Outcome of a jackpot win-roll. [amount] is the credits awarded
 * (0 on miss); [tierIndex] / [tierPayoutPct] identify which wheel
 * segment was picked on a hit (-1 / 0.0 on miss) so the web layer can
 * animate the wheel landing on the right wedge.
 */
data class JackpotRoll(
    val amount: Long,
    val tierIndex: Int,
    val tierPayoutPct: Double,
) {
    companion object {
        val MISS: JackpotRoll = JackpotRoll(0L, -1, 0.0)
    }

    /**
     * Combine two rolls — used by CasinoHoldem where ante and call
     * legs each get their own roll. Amounts sum; the tier shown is
     * the one from the higher-paying hit (so a stacked win surfaces
     * the bigger slice in the UI).
     */
    operator fun plus(other: JackpotRoll): JackpotRoll {
        if (other.amount <= 0L) return this
        if (this.amount <= 0L) return other
        return if (other.amount >= this.amount) {
            JackpotRoll(this.amount + other.amount, other.tierIndex, other.tierPayoutPct)
        } else {
            JackpotRoll(this.amount + other.amount, this.tierIndex, this.tierPayoutPct)
        }
    }
}

/**
 * Casino games that feed the jackpot pool, paired with their canonical
 * RTP and a [eligibleForJackpot] policy flag. RTP is the highest
 * plausible value across the game's bet variants (Banker for Baccarat,
 * basic-strategy for Blackjack, etc.) — the per-guild RTP eligibility
 * gate uses it to decide whether a game is "too generous" to also pay
 * a jackpot. Pinned alongside the per-game test suites in
 * `database.economy` / `database.blackjack`; if a game's payout
 * schedule is tuned, the value here moves with it.
 *
 * [eligibleForJackpot] is a global structural carve-out for games
 * where the RTP gate isn't the right proxy. HighLow honestly returns
 * 12/13 RTP, but the player can pick direction against an anchor
 * dealt from 2..12 — at the extremes that wins ~85 % of the time
 * with a tiny multiplier. `rollOnWin` fires per *win*, not per credit
 * of house edge, so HighLow lets a player rack up free jackpot rolls
 * while bleeding only the honest 7.7 % edge. The flag disables the
 * win-roll while leaving `divertOnLoss` untouched — losses still feed
 * the pool, wins just don't claim from it.
 */
enum class JackpotGame(val rtp: Double, val eligibleForJackpot: Boolean = true) {
    COINFLIP(1.0),     // 50/50 fair, 2× payout — no house edge by design.
    BLACKJACK(0.99),   // Basic strategy hovers ~99-100%; conservatively 0.99.
    BACCARAT(0.99),    // Banker ~98.94%, Player ~98.76% — pin to the higher.
    ROULETTE(0.973),   // 36/37 European wheel — uniform across bet types.
    HOLDEM(0.97),      // Casino Hold'em vs dealer; ~2-3% edge industry-wide.
    HORSE_RACING(0.92, eligibleForJackpot = false), // 0.92 RTP across Win/Place/Show, but Show-on-favourite (H1) wins ~75 % of races — same farming surface that flagged HIGHLOW for the carve-out. A farmer staking at anchor on Show-H1 turns the −10 c/race base EV into a net positive once the pool grows past ~5 k credits (~+0.75 % jackpot roll/race × pool). RTP gate is the wrong proxy (filters by edge, not win rate), so go structural. Loss-tribute only.
    HIGHLOW(0.923, eligibleForJackpot = false), // Honest 12/13 RTP but ~85 % win rate at extreme anchors farms rolls; loss-tribute only.
    KENO(0.92),        // 0.83-0.92 band; pin to the top so the gate is honest.
    PLINKO(0.89),      // ~11% edge across LOW/MEDIUM/HIGH profiles; pinned by PlinkoTest.
    SLOTS(0.890),      // ~11% house edge; closed-form pinned by SlotMachineTest.
    WHEEL_OF_FORTUNE(0.88), // Per-pick RTP cluster 0.87-0.90; pinned by WheelOfFortuneTest.
    SCRATCH(0.875),    // ~12.5% edge; pinned by ScratchCard.expectedRtp().
    DICE(0.833),       // 5/6 — 5× payout at 1/6 odds.
}

/**
 * Two casino-side feeders for the per-guild jackpot pool:
 *
 *   - [rollOnWin] — on every minigame WIN, roll a small probability
 *     and on a hit spin the per-guild [JackpotWheel] for a tier-based
 *     share of the pool. Returns a [JackpotRoll] carrying the awarded
 *     amount and the picked tier so the casino UI can animate the
 *     wheel landing on the matching wedge.
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
     * Default ceiling on a game's RTP for it to remain jackpot-eligible.
     * Whole-number percent (0-100). 0 (default) disables the gate so
     * unconfigured guilds keep the pre-PR behaviour — every game rolls
     * for the jackpot. Recommended value 95: blocks Coinflip (1.0),
     * Blackjack (~0.99), Baccarat (~0.99), Roulette (0.973) — games that
     * already return ~all stake on their own — while keeping Slots,
     * Scratch, Dice, Keno, and HighLow eligible. The intent is "high-RTP
     * games don't *also* need a jackpot sweetener; jackpots compensate
     * for house edge."
     */
    const val DEFAULT_RTP_MAX_PCT: Long = 0L

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
     * If the random roll hits, spin the per-guild payout wheel (see
     * [JackpotWheel]) for a tier and atomically credit that share of
     * the pool to [user] (already locked by [WagerHelper.checkAndLock]).
     * Returns [JackpotRoll.MISS] on miss / empty pool / failed gate;
     * otherwise a [JackpotRoll] carrying the amount won, the picked
     * segment's index, and its payout fraction so the casino UI can
     * animate the wheel landing on the right wedge.
     *
     * The hit probability is per-guild configurable via
     * `JACKPOT_WIN_PCT`; defaults to [DEFAULT_WIN_PROBABILITY]. The
     * base probability is scaled linearly by `stake / anchor`, where
     * `anchor` is the per-guild `JACKPOT_STAKE_ANCHOR` config
     * (default [DEFAULT_STAKE_ANCHOR]). A min-wager autoclicker can't
     * farm the pool — a 10-credit play against a 500 anchor rolls at
     * 0.02 % even when the configured base is 1 %. Bets at or above
     * the anchor cap at full base probability. Decoupled from each
     * game's max stake so admins can raise stakes arbitrarily without
     * shrinking jackpot odds.
     *
     * Games carrying [JackpotGame.eligibleForJackpot] = `false` skip
     * the roll entirely — they're hard-coded ineligible regardless of
     * RTP or any per-guild config, because the RTP gate is the wrong
     * proxy for them (typically high-win-rate, honest-edge designs).
     */
    fun rollOnWin(
        jackpotService: JackpotService,
        configService: ConfigService,
        userService: UserService,
        user: UserDto,
        guildId: Long,
        stake: Long,
        game: JackpotGame,
        random: Random
    ): JackpotRoll {
        if (!game.eligibleForJackpot) return JackpotRoll.MISS
        val baseProbability = winProbability(configService, guildId)
        val anchor = stakeAnchor(configService, guildId)
        val scale = (stake.toDouble() / anchor.toDouble()).coerceIn(0.0, 1.0)
        val effective = baseProbability * scale
        if (random.nextDouble() >= effective) return JackpotRoll.MISS

        // Post-fraud structural gates. Each defaults to "disabled" so an
        // unconfigured guild's behaviour is unchanged. When enabled, a
        // failed gate looks identical to a missed roll — pool keeps
        // growing, no payout, no exception thrown into the wager service.
        if (jackpotService.isOnCooldown(guildId, user.discordId)) return JackpotRoll.MISS
        if (!jackpotService.isActive(guildId, user.discordId)) return JackpotRoll.MISS
        if (!isEligibleByRtp(game, configService, guildId)) return JackpotRoll.MISS

        val segments = JackpotWheel.parse(wheelSegmentsConfig(configService, guildId))
        val spin = JackpotWheel.spin(segments, random)
        val won = jackpotService.awardJackpot(guildId, spin.payoutPct)
        if (won == 0L) return JackpotRoll.MISS
        jackpotService.recordWin(guildId, user.discordId, won)
        user.socialCredit = (user.socialCredit ?: 0L) + won
        userService.updateUser(user)
        return JackpotRoll(amount = won, tierIndex = spin.tierIndex, tierPayoutPct = spin.payoutPct)
    }

    /**
     * Raw [JACKPOT_WHEEL_SEGMENTS] config value for [guildId], or null
     * when unset. Lets [JackpotWheel.parse] handle the default-on-miss
     * fallback in one place.
     */
    fun wheelSegmentsConfig(configService: ConfigService, guildId: Long): String? {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_WHEEL_SEGMENTS.configValue,
            guildId.toString()
        )
        return cfg?.value
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

    /**
     * Live RTP-eligibility ceiling for [guildId] in whole-number percent
     * (0-100), parsed from `JACKPOT_RTP_MAX_PCT`. Returns
     * [DEFAULT_RTP_MAX_PCT] (0 = gate disabled) when unset, unparseable,
     * or negative; clamps anything above 100 down to 100.
     */
    fun rtpMaxPct(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.JACKPOT_RTP_MAX_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toLongOrNull() ?: return DEFAULT_RTP_MAX_PCT
        return pct.coerceIn(0L, 100L)
    }

    /**
     * Returns `true` when [game]'s canonical RTP is at or below the
     * configured per-guild ceiling, or when the gate is disabled
     * (`JACKPOT_RTP_MAX_PCT` = 0). The rationale is that jackpots exist
     * to compensate for house edge — a game already returning ~all stake
     * to the player (Coinflip, Blackjack, Baccarat) doesn't need a
     * jackpot bolted on top, and admins can opt out of paying one by
     * setting a ceiling like 95.
     */
    fun isEligibleByRtp(game: JackpotGame, configService: ConfigService, guildId: Long): Boolean {
        val maxPct = rtpMaxPct(configService, guildId)
        if (maxPct == 0L) return true
        return game.rtp * 100.0 <= maxPct.toDouble()
    }
}
