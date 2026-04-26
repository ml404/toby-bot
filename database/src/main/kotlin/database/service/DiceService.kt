package database.service

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
            val newPrice: Double? = null
        ) : RollOutcome

        data class Lose(
            val stake: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L
        ) : RollOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : RollOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : RollOutcome
        data class InvalidStake(val min: Long, val max: Long) : RollOutcome
        data class InvalidPrediction(val min: Int, val max: Int) : RollOutcome
        data object UnknownUser : RollOutcome
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
        val initial = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Dice.MIN_STAKE, Dice.MAX_STAKE
        )
        var soldCoins = 0L
        var newPrice: Double? = null
        val resolved = when (initial) {
            is BalanceCheck.InvalidStake -> return RollOutcome.InvalidStake(initial.min, initial.max)
            BalanceCheck.UnknownUser -> return RollOutcome.UnknownUser
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp) return RollOutcome.InsufficientCredits(initial.stake, initial.have)
                val user = userService.getUserByIdForUpdate(discordId, guildId)
                    ?: return RollOutcome.UnknownUser
                val topUp = CasinoTopUpHelper.ensureCreditsForWager(
                    tradeService, marketService, userService,
                    user, guildId, currentBalance = initial.have, stake = stake
                )
                when (topUp) {
                    is TopUpResult.InsufficientCoins ->
                        return RollOutcome.InsufficientCoinsForTopUp(topUp.needed, topUp.have)
                    TopUpResult.MarketUnavailable ->
                        return RollOutcome.InsufficientCredits(initial.stake, initial.have)
                    is TopUpResult.ToppedUp -> {
                        soldCoins = topUp.soldCoins
                        newPrice = topUp.newPrice
                        BalanceCheck.Ok(topUp.user, topUp.balance)
                    }
                }
            }
            is BalanceCheck.Ok -> initial
        }

        val roll = dice.roll(predicted, random)
        val r = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, roll.multiplier)
        return if (roll.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, userService, resolved.user, guildId, random)
            RollOutcome.Win(
                stake = stake,
                payout = r.payout,
                net = r.net,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = r.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = soldCoins,
                newPrice = newPrice
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            RollOutcome.Lose(
                stake = stake,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = r.newBalance,
                soldTobyCoins = soldCoins,
                newPrice = newPrice,
                lossTribute = tribute
            )
        }
    }
}
