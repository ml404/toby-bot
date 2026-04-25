package database.service

import database.economy.SlotMachine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic spin path for the `/slots` minigame. Both the Discord `/slots`
 * command and the web `/casino/{guildId}/slots` page call through here so
 * the debit/credit maths and the random draw only live in one place.
 *
 * Pattern mirrors [EconomyTradeService]: lock the user row first
 * ([UserService.getUserByIdForUpdate]), validate inputs against the live
 * balance, mutate, persist. The whole thing runs inside one
 * `@Transactional` boundary so concurrent `/slots` invocations from the
 * same user (Discord + web at once, or just spam-clicking) can't double-
 * spend the stake.
 */
@Service
@Transactional
class SlotsService(
    private val userService: UserService,
    private val machine: SlotMachine = SlotMachine(),
    private val random: Random = Random.Default
) {

    sealed interface SpinOutcome {
        data class Win(
            val stake: Long,
            val multiplier: Long,
            val payout: Long,
            val net: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long
        ) : SpinOutcome

        data class Lose(
            val stake: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long
        ) : SpinOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : SpinOutcome
        data class InvalidStake(val min: Long, val max: Long) : SpinOutcome
        data object UnknownUser : SpinOutcome
    }

    fun spin(discordId: Long, guildId: Long, stake: Long): SpinOutcome {
        if (stake < SlotMachine.MIN_STAKE || stake > SlotMachine.MAX_STAKE) {
            return SpinOutcome.InvalidStake(SlotMachine.MIN_STAKE, SlotMachine.MAX_STAKE)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return SpinOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return SpinOutcome.InsufficientCredits(stake, balance)

        val pull = machine.pull(random)
        // payout = multiplier × stake on a win, 0 on a loss. Net change to
        // the user's balance is (payout - stake): positive on wins (we eat
        // their stake first then pay them out), -stake on losses.
        val payout = pull.multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        val newBalance = user.socialCredit ?: 0L

        return if (pull.isWin) {
            SpinOutcome.Win(
                stake = stake,
                multiplier = pull.multiplier,
                payout = payout,
                net = net,
                symbols = pull.symbols,
                newBalance = newBalance
            )
        } else {
            SpinOutcome.Lose(stake = stake, symbols = pull.symbols, newBalance = newBalance)
        }
    }
}
