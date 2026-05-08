package database.service

import common.casino.CasinoCommonFailure
import database.dto.ConfigDto
import database.economy.Coinflip
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic flip path for the `/coinflip` minigame. Both Discord
 * `/coinflip` and the web `/casino/{guildId}/coinflip` page call through
 * here, mirroring the [SlotsService] pattern: lock the user row first
 * via [UserService.getUserByIdForUpdate], validate inputs, mutate,
 * persist, all inside a single `@Transactional` boundary.
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class CoinflipService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val botSuspicionService: CoinflipBotSuspicionService,
    private val coinflip: Coinflip = Coinflip(),
    private val random: Random = Random.Default
) {

    companion object {
        /** Per-bet house-edge slope as the suspicion streak grows. 2.5 % per
         *  consecutive bot-like click means streak 12 saturates the default
         *  30 % cap. */
        const val EDGE_PCT_PER_STREAK: Double = 2.5

        /** Default ceiling on the bot-suspicion house edge, in whole percent.
         *  Active when `JACKPOT_*` admins haven't tuned `COINFLIP_BOT_EDGE_MAX_PCT`. */
        const val DEFAULT_EDGE_MAX_PCT: Long = 30L

        /** Hard upper bound for the configurable cap. 50 % of bets becoming
         *  forced losses is already aggressive — beyond that the game is
         *  unplayable for false positives. */
        const val MAX_EDGE_MAX_PCT: Long = 50L
    }

    sealed interface FlipOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : FlipOutcome

        data class Lose(
            val stake: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : FlipOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            FlipOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            FlipOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            FlipOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : FlipOutcome, CasinoCommonFailure.UnknownUser
    }

    fun flip(
        discordId: Long,
        guildId: Long,
        stake: Long,
        predicted: Coinflip.Side,
        autoTopUp: Boolean = false,
        clickX: Int? = null,
        clickY: Int? = null,
        mouseMoved: Boolean? = null,
    ): FlipOutcome {
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.COINFLIP_MIN_STAKE, guildId, default = Coinflip.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.COINFLIP_MAX_STAKE, guildId, default = Coinflip.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return FlipOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return FlipOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return FlipOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return FlipOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        // Map the user's recent click pattern into a forced-loss probability
        // before running the fair flip. A streak of 0 (humans, Discord, first
        // bet) means boost = 0 and the original 50/50 is preserved exactly.
        val streak = botSuspicionService.recordAndScore(discordId, guildId, clickX, clickY, mouseMoved)
        val maxEdgePct = configService.cfgLong(
            ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT, guildId,
            default = DEFAULT_EDGE_MAX_PCT, min = 0L,
        ).coerceAtMost(MAX_EDGE_MAX_PCT)
        val houseEdge = (streak * EDGE_PCT_PER_STREAK / 100.0).coerceIn(0.0, maxEdgePct / 100.0)
        // Translate house edge → biased lose probability. A 30 % edge means
        // RTP 0.7, i.e. 65 % lose / 35 % win. We pre-roll the (edge) chunk
        // as a forced loss; the rest falls through to the fair flip.
        val loseProbabilityBoost = houseEdge

        val flip = coinflip.flip(predicted, random, loseProbabilityBoost)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, flip.multiplier)
        return if (flip.isWin) {
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, random,
            )
            FlipOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            FlipOutcome.Lose(
                stake = stake,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}
