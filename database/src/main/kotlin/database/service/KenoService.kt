package database.service

import database.economy.Keno
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic play path for the `/keno` minigame. Same lock-then-mutate
 * pattern as the other one-shot wager services ([BaccaratService],
 * [CoinflipService], [DiceService]) via [UserService.getUserByIdForUpdate]:
 *   - validate stake against [Keno.MIN_STAKE]..[Keno.MAX_STAKE]
 *   - lock the user row
 *   - confirm balance ≥ stake (optionally sell TOBY for the shortfall
 *     via [CasinoTopUpHelper] when [autoTopUp] is set)
 *   - validate picks are 1..10 distinct values in the 1..80 pool
 *   - draw 20 numbers from the same pool with the injected [Random]
 *   - resolve the (picks, draws) hand via [Keno.play]
 *   - apply the resulting fractional multiplier through
 *     [WagerHelper.applyMultiplier]
 *   - feed [JackpotHelper] on win/loss
 *
 * Unlike baccarat there is no Push outcome — keno's paytable is
 * win-or-lose. A round with zero hits (or a non-paying hit count) is
 * a Lose with multiplier 0.0.
 */
@Service
@Transactional
class KenoService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val keno: Keno = Keno(),
    private val random: Random = Random.Default
) {

    sealed interface PlayOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val picks: List<Int>,
            val draws: List<Int>,
            val hits: Int,
            val multiplier: Double,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : PlayOutcome

        data class Lose(
            val stake: Long,
            val picks: List<Int>,
            val draws: List<Int>,
            val hits: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L
        ) : PlayOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : PlayOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : PlayOutcome
        data class InvalidStake(val min: Long, val max: Long) : PlayOutcome
        data class InvalidPicks(val min: Int, val max: Int, val poolMax: Int) : PlayOutcome
        data object UnknownUser : PlayOutcome
    }

    /**
     * Auto-pick a [count]-spot ticket. Used by Discord when the player
     * doesn't supply explicit picks; web clients send their own picks.
     */
    fun quickPick(count: Int): List<Int> = keno.quickPick(count, random)

    /**
     * Per-spots top multiplier (e.g. for command-line previews so the
     * user can see "10-spot pays up to 165,000×" before committing).
     */
    fun maxMultiplier(spots: Int): Double = keno.maxMultiplier(spots)

    /** Whole paytable, for embeds / page rendering. */
    val paytable: Map<Int, List<Double>> get() = Keno.PAYTABLE

    fun play(
        discordId: Long,
        guildId: Long,
        stake: Long,
        picks: List<Int>,
        autoTopUp: Boolean = false
    ): PlayOutcome {
        // Validate picks BEFORE touching the wallet — saves a top-up
        // sell on a malformed request.
        val pickSet = picks.toSet()
        if (pickSet.size != picks.size ||
            pickSet.size !in Keno.MIN_SPOTS..Keno.MAX_SPOTS ||
            pickSet.any { it !in Keno.POOL_RANGE }
        ) {
            return PlayOutcome.InvalidPicks(Keno.MIN_SPOTS, Keno.MAX_SPOTS, Keno.POOL_SIZE)
        }

        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, Keno.MIN_STAKE, Keno.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return PlayOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return PlayOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return PlayOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return PlayOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val draws = keno.drawNumbers(random)
        // Engine validates again (cheap, defence in depth) — should never
        // fail given the upstream check, but if it does we re-surface
        // InvalidPicks so the controller can reject with the same shape.
        val hand = keno.play(pickSet, draws)
            ?: return PlayOutcome.InvalidPicks(Keno.MIN_SPOTS, Keno.MAX_SPOTS, Keno.POOL_SIZE)

        val wager = WagerHelper.applyMultiplier(
            userService, resolved.user, resolved.balance, stake, hand.multiplier
        )
        return if (hand.isWin) {
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId, random
            )
            PlayOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                picks = hand.picks,
                draws = hand.draws,
                hits = hand.hits,
                multiplier = hand.multiplier,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            PlayOutcome.Lose(
                stake = stake,
                picks = hand.picks,
                draws = hand.draws,
                hits = hand.hits,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute
            )
        }
    }
}
