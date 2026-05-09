package database.service

import database.dto.ConfigDto

/**
 * Per-guild daily match-numbers lottery configuration getters.
 * Mirrors the shape of [JackpotHelper] config getters: read the admin
 * config, parse, validate range, fall back to a defined default. All
 * of these are read on every job tick / ticket buy — kept side-effect-
 * free so they can be safely called outside a transaction.
 *
 * The daily flow ties into [JackpotLotteryService.openMatchLottery] /
 * [JackpotLotteryService.buyMatchTicket] / [JackpotLotteryService.drawMatchLottery]
 * — the values here drive the daily draw's economics.
 */
object LotteryHelper {

    /**
     * Pick 5 of 1-49 — Lotto-style. Hardcoded for now because the prize
     * tier schedule was tuned to these odds; varying pick count would
     * need a fresh schedule and is out of scope for the daily MVP.
     */
    const val MATCH_PICK_COUNT: Int = 5
    const val MATCH_NUMBER_MAX: Int = 49

    /** Daily auto-draw is opt-in per guild. */
    const val DEFAULT_DAILY_ENABLED: Boolean = false

    /** Default credits charged per match-numbers ticket. */
    const val DEFAULT_DAILY_TICKET_PRICE: Long = 50L
    const val MAX_DAILY_TICKET_PRICE: Long = 1_000_000L

    /**
     * Default % of the per-guild jackpot pool that seeds each day's
     * prize pool at open. 5% of a 200k pool = 10k seed; combined with
     * ticket revenue, that's the daily prize budget.
     */
    const val DEFAULT_DAILY_SEED_PCT: Long = 5L
    const val MAX_DAILY_SEED_PCT: Long = 100L

    /**
     * Default % of every ticket sale that routes to the per-guild
     * jackpot pool. The remainder (70%) feeds the day's prize pool.
     * Higher = stronger sink; lower = bigger daily prizes from
     * engagement.
     */
    const val DEFAULT_DAILY_REVENUE_JACKPOT_PCT: Long = 30L
    const val MAX_DAILY_REVENUE_JACKPOT_PCT: Long = 100L

    /**
     * Tier prize percentages for a match-numbers draw. Order: 5/5, 4/5,
     * 3/5, 2/5. Sum = 100; un-won tier shares roll back into the
     * per-guild jackpot pool via the remainder-handling in
     * [JackpotLotteryService.drawMatchLottery]. 0 / 1 matches pay
     * nothing — those tickets are the sink.
     */
    val TIER_PCTS_5_4_3_2: IntArray = intArrayOf(60, 25, 10, 5)

    /** Live boolean toggle for the daily auto-draw. */
    fun dailyEnabled(configService: ConfigService, guildId: Long): Boolean {
        val raw = configService.getConfigByName(
            ConfigDto.Configurations.LOTTERY_DAILY_ENABLED.configValue,
            guildId.toString()
        )?.value?.trim()?.lowercase()
        return when (raw) {
            "true", "1", "yes", "on" -> true
            null -> DEFAULT_DAILY_ENABLED
            else -> false
        }
    }

    /** Live ticket price for the daily lottery. */
    fun dailyTicketPrice(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.LOTTERY_DAILY_TICKET_PRICE.configValue,
            guildId.toString()
        )
        val price = cfg?.value?.toLongOrNull() ?: return DEFAULT_DAILY_TICKET_PRICE
        return price.coerceIn(1L, MAX_DAILY_TICKET_PRICE)
    }

    /** Live seed % of the jackpot pool to fund the day's prize pool. */
    fun dailySeedPct(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.LOTTERY_DAILY_SEED_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toLongOrNull() ?: return DEFAULT_DAILY_SEED_PCT
        return pct.coerceIn(1L, MAX_DAILY_SEED_PCT)
    }

    /** Live % of ticket revenue routed to jackpot (rest = prize pool). */
    fun dailyRevenueJackpotPct(configService: ConfigService, guildId: Long): Long {
        val cfg = configService.getConfigByName(
            ConfigDto.Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT.configValue,
            guildId.toString()
        )
        val pct = cfg?.value?.toLongOrNull() ?: return DEFAULT_DAILY_REVENUE_JACKPOT_PCT
        return pct.coerceIn(0L, MAX_DAILY_REVENUE_JACKPOT_PCT)
    }
}
