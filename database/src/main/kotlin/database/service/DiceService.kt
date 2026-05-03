package database.service

import common.casino.CasinoCommonFailure
import database.economy.Dice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic roll path for the `/dice` minigame. Same lock-then-mutate
 * pattern as [SlotsService] / [CoinflipService] via
 * [UserService.getUserByIdForUpdate] so concurrent rolls from the same
 * user (Discord + web at once, or spam-clicking) can't double-spend.
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class DiceService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val dice: Dice = Dice(),
    private val random: Random = Random.Default
) {

    sealed interface RollOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : RollOutcome

        data class Lose(
            val stake: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : RollOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            RollOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            RollOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            RollOutcome, CasinoCommonFailure.InvalidStake
        data class InvalidPrediction(val min: Int, val max: Int) : RollOutcome
        data object UnknownUser : RollOutcome, CasinoCommonFailure.UnknownUser
    }

    fun roll(
        discordId: Long,
        guildId: Long,
        stake: Long,
        predicted: Int,
        autoTopUp: Boolean = false
    ): RollOutcome {
        if (!dice.isValidPrediction(predicted)) {
            return RollOutcome.InvalidPrediction(1, dice.sidesCount)
        }
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, Dice.MIN_STAKE, Dice.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return RollOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return RollOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return RollOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return RollOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val roll = dice.roll(predicted, random)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, roll.multiplier)
        return if (roll.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, configService, userService, resolved.user, guildId, random)
            RollOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            RollOutcome.Lose(
                stake = stake,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}
