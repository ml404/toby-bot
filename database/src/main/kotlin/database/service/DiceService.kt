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
            val newBalance: Long
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
        if (stake < Dice.MIN_STAKE || stake > Dice.MAX_STAKE) {
            return RollOutcome.InvalidStake(Dice.MIN_STAKE, Dice.MAX_STAKE)
        }
        if (!dice.isValidPrediction(predicted)) {
            return RollOutcome.InvalidPrediction(1, dice.sidesCount)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return RollOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return RollOutcome.InsufficientCredits(stake, balance)

        val roll = dice.roll(predicted, random)
        val payout = roll.multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        val newBalance = user.socialCredit ?: 0L

        return if (roll.isWin) {
            RollOutcome.Win(
                stake = stake,
                payout = payout,
                net = net,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = newBalance
            )
        } else {
            RollOutcome.Lose(
                stake = stake,
                landed = roll.landed,
                predicted = roll.predicted,
                newBalance = newBalance
            )
        }
    }
}
