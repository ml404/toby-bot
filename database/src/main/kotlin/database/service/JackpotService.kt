package database.service

import database.dto.TobyCoinJackpotDto
import database.dto.TobyCoinJackpotWinnerDto
import database.persistence.TobyCoinJackpotPersistence
import database.persistence.TobyCoinJackpotWinnerPersistence
import database.persistence.VoiceCreditDailyPersistence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Atomic operations on the per-guild jackpot pool.
 *
 * The pool is fed by a flat fee on every Toby Coin trade (see
 * [EconomyTradeService]). Casino minigame wins roll a small chance to
 * hit the jackpot — on a hit the per-guild [common.economy.JackpotWheel]
 * is spun for a tier and the player banks that fraction of the pool
 * (default tiers `80:1,10:5,5:10,4:20,1:50` — EV ~3.1% per win).
 *
 * Two structural gates layer on top of the basic roll:
 *  - [isOnCooldown] blocks a recent winner from sweeping again within
 *    `JACKPOT_WINNER_COOLDOWN_DAYS`. Each successful win records the
 *    timestamp via [recordWin].
 *  - [isActive] requires the candidate winner to have actually been
 *    around in the last `JACKPOT_ACTIVITY_WINDOW_DAYS` (sourced from
 *    `voice_credit_daily`). A drive-by user with one bet can't run the
 *    pool dry.
 *
 * All defaults match the historic single-winner-takes-all behaviour, so
 * a guild that never touches the new configs sees no change.
 *
 * All mutations go through pessimistic row locks so two concurrent
 * winners can't both bank the same pool.
 */
@Service
@Transactional
class JackpotService(
    private val persistence: TobyCoinJackpotPersistence,
    private val configService: ConfigService,
    private val winnerPersistence: TobyCoinJackpotWinnerPersistence,
    private val voiceCreditDailyPersistence: VoiceCreditDailyPersistence,
) {

    /** Current pool size; 0 if no row yet for this guild. */
    fun getPool(guildId: Long): Long =
        persistence.getByGuild(guildId)?.pool ?: 0L

    /**
     * Live win probability for [guildId] expressed as a percentage
     * (so 1.0 means "1% chance per casino-game win"). Reads the
     * admin-set `JACKPOT_WIN_PCT` config and falls back to the
     * [JackpotHelper.DEFAULT_WIN_PROBABILITY] default. Used by the
     * casino-page banner so it shows the actual configured chance
     * instead of a hardcoded "1%".
     */
    fun winProbabilityPct(guildId: Long): Double =
        JackpotHelper.winProbability(configService, guildId) * 100.0

    /**
     * Banner-ready string for the live win probability — same source as
     * [winProbabilityPct] but formatted to retain admin-set precision so
     * `0.005` and `0.0005` render distinctly instead of both rounding to
     * `0.01`. Trims trailing zeros (`1`, `0.5`, `0.05`, `0.005`,
     * `0.0005`); the casino banner appends the `%` sign.
     */
    fun winProbabilityDisplay(guildId: Long): String {
        val pct = winProbabilityPct(guildId)
        if (pct == 0.0) return "0"
        return BigDecimal.valueOf(pct)
            .setScale(WIN_PCT_DISPLAY_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Live jackpot stake anchor for [guildId] in whole credits — reads
     * the admin-set `JACKPOT_STAKE_ANCHOR` config and falls back to
     * [JackpotHelper.DEFAULT_STAKE_ANCHOR]. Surfaced so the casino-page
     * banner can name the actual scaling threshold the win-roll uses
     * (`stake / anchor`, capped at 1.0); without this the banner has
     * to either hardcode a value or misrepresent the divisor.
     */
    fun stakeAnchor(guildId: Long): Long =
        JackpotHelper.stakeAnchor(configService, guildId)

    /**
     * Live RTP-eligibility ceiling for [guildId] in whole-number percent
     * (0-100; 0 = gate disabled). Surfaced so the casino-page banner can
     * name the configured ceiling when explaining why a particular game
     * isn't eligible to roll.
     */
    fun rtpMaxPct(guildId: Long): Long =
        JackpotHelper.rtpMaxPct(configService, guildId)

    /**
     * Returns true when [game] is eligible to roll for the jackpot in
     * [guildId] under the configured RTP ceiling, or when the gate is
     * disabled (`JACKPOT_RTP_MAX_PCT` = 0). Banner-side mirror of the
     * gate inside [JackpotHelper.rollOnWin] so the page can warn the
     * user up front instead of only at win-time.
     */
    fun isEligibleByRtp(guildId: Long, game: JackpotGame): Boolean =
        JackpotHelper.isEligibleByRtp(game, configService, guildId)

    /**
     * Parsed payout-wheel segments for [guildId] — reads the
     * `JACKPOT_WHEEL_SEGMENTS` config and falls back to
     * [common.economy.JackpotWheel.DEFAULT_SEGMENTS] when unset or
     * malformed. Surfaced for the casino page so the wheel UI can
     * render the same segments the server spins.
     */
    fun wheelSegments(guildId: Long): List<common.economy.JackpotWheel.Segment> =
        common.economy.JackpotWheel.parse(JackpotHelper.wheelSegmentsConfig(configService, guildId))

    /**
     * Raw `JACKPOT_WHEEL_SEGMENTS` config value for [guildId] (or null
     * when unset). Used by the moderation UI to populate the editor —
     * a null result tells the page "show the default placeholder".
     */
    fun wheelSegmentsConfigValue(guildId: Long): String? =
        JackpotHelper.wheelSegmentsConfig(configService, guildId)

    companion object {
        // 4dp covers the smallest probability the saved-percent / 100
        // round-trip can express meaningfully — e.g. an admin saving
        // "0.0005" yields a 0.0005% banner. Anything finer rounds to 0.
        private const val WIN_PCT_DISPLAY_SCALE = 4
    }

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
     * Pay out a share of the pool atomically. The caller supplies
     * [payoutFraction] in `(0, 1]` — picked by [JackpotWheel.spin] on
     * the rolling-win path or fixed by the lottery service for draws.
     * Returns the amount paid; the caller is responsible for crediting
     * the winner. The remainder stays in the pool and re-seeds the
     * next cycle.
     */
    fun awardJackpot(guildId: Long, payoutFraction: Double): Long {
        val row = lockOrCreate(guildId)
        if (row.pool == 0L) return 0L
        val fraction = payoutFraction.coerceIn(0.0, 1.0)
        if (fraction <= 0.0) return 0L
        val won = kotlin.math.floor(row.pool * fraction).toLong().coerceAtMost(row.pool).coerceAtLeast(0L)
        if (won == 0L) return 0L
        row.pool -= won
        persistence.upsert(row)
        return won
    }

    /**
     * Admin-only: zero the pool without paying anyone. Returns the
     * amount that was drained, so the caller can show "reset 12,345
     * credits" in the audit response. Use when a player has
     * pathologically inflated the pool via spam-tribute and rolling
     * back the activity is the cleanest fix.
     */
    fun resetPool(guildId: Long): Long {
        val row = lockOrCreate(guildId)
        val drained = row.pool
        if (drained == 0L) return 0L
        row.pool = 0L
        persistence.upsert(row)
        return drained
    }

    /**
     * Persist a jackpot win for the cooldown gate. Called after a
     * successful payout from `JackpotHelper.rollOnWin` or from
     * `JackpotLotteryService.drawLottery` so a single user can't sweep
     * both a casino-roll jackpot and a lottery jackpot within the
     * configured cooldown window.
     */
    fun recordWin(guildId: Long, discordId: Long, amount: Long, at: Instant = Instant.now()) {
        if (amount <= 0L) return
        winnerPersistence.upsert(
            TobyCoinJackpotWinnerDto(
                guildId = guildId,
                discordId = discordId,
                lastWonAt = at,
                lastWonAmount = amount,
            )
        )
    }

    /**
     * Returns true when [discordId] won a jackpot in [guildId] within
     * the last `JACKPOT_WINNER_COOLDOWN_DAYS` (gate disabled when the
     * config is 0 / unset, in which case this always returns false).
     */
    fun isOnCooldown(guildId: Long, discordId: Long, at: Instant = Instant.now()): Boolean {
        val days = JackpotHelper.winnerCooldownDays(configService, guildId)
        if (days <= 0L) return false
        val row = winnerPersistence.get(guildId, discordId) ?: return false
        val elapsed = Duration.between(row.lastWonAt, at)
        return elapsed < Duration.ofDays(days)
    }

    /**
     * Returns true when [discordId] meets the per-guild activity
     * threshold — i.e. has earned credit-cap-eligible amounts on at
     * least `JACKPOT_ACTIVITY_MIN_DAYS` distinct days within the last
     * `JACKPOT_ACTIVITY_WINDOW_DAYS`. Gate disabled (returns true
     * unconditionally) when the window config is 0 / unset.
     *
     * `voice_credit_daily` is the activity proxy because every existing
     * earn path (voice, commands, intros, web UI) routes through it,
     * so a user who hasn't shown up at all won't have rows.
     */
    fun isActive(guildId: Long, discordId: Long, at: Instant = Instant.now()): Boolean {
        val window = JackpotHelper.activityWindowDays(configService, guildId)
        if (window <= 0L) return true
        val minDays = JackpotHelper.activityMinDays(configService, guildId)
        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        // Inclusive `from` covers exactly `window` days of history including today.
        val from = today.minusDays(window - 1L)
        val days = voiceCreditDailyPersistence.countDaysSince(discordId, guildId, from)
        return days >= minDays
    }

    private fun lockOrCreate(guildId: Long): TobyCoinJackpotDto {
        persistence.getByGuildForUpdate(guildId)?.let { return it }
        // First fee for this guild — seed and re-read with the write lock.
        persistence.upsert(TobyCoinJackpotDto(guildId = guildId, pool = 0L))
        return persistence.getByGuildForUpdate(guildId)
            ?: error("Jackpot row for guild $guildId could not be locked after creation")
    }
}
