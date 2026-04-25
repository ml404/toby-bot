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
 */
@Service
@Transactional
class DiceService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
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
            val jackpotPayout: Long = 0L
        ) : RollOutcome

        data class Lose(
            val stake: Long,
            val landed: Int,
            val predicted: Int,
            val newBalance: Long
        ) : RollOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : RollOutcome
        data class InvalidStake(val min: Long, val max: Long) : RollOutcome
        data class InvalidPrediction(val min: Int, val max: Int) : RollOutcome
        data object UnknownUser : RollOutcome
    }

    fun roll(discordId: Long, guildId: Long, stake: Long, predicted: Int): RollOutcome {
        if (!dice.isValidPrediction(predicted)) {
            return RollOutcome.InvalidPrediction(1, dice.sidesCount)
        }
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Dice.MIN_STAKE, Dice.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> RollOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> RollOutcome.UnknownUser
            is BalanceCheck.Insufficient -> RollOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val roll = dice.roll(predicted, random)
                val r = WagerHelper.applyMultiplier(userService, check.user, check.balance, stake, roll.multiplier)
                if (roll.isWin) {
                    val jackpot = JackpotHelper.rollOnWin(jackpotService, userService, check.user, guildId, random)
                    RollOutcome.Win(
                        stake = stake,
                        payout = r.payout,
                        net = r.net,
                        landed = roll.landed,
                        predicted = roll.predicted,
                        newBalance = r.newBalance + jackpot,
                        jackpotPayout = jackpot
                    )
                } else {
                    RollOutcome.Lose(
                        stake = stake,
                        landed = roll.landed,
                        predicted = roll.predicted,
                        newBalance = r.newBalance
                    )
                }
            }
        }
    }
}
