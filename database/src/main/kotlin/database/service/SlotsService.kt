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
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, SlotMachine.MIN_STAKE, SlotMachine.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> SpinOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> SpinOutcome.UnknownUser
            is BalanceCheck.Insufficient -> SpinOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val pull = machine.pull(random)
                val r = WagerHelper.applyMultiplier(userService, check.user, check.balance, stake, pull.multiplier)
                if (pull.isWin) {
                    SpinOutcome.Win(
                        stake = stake,
                        multiplier = pull.multiplier,
                        payout = r.payout,
                        net = r.net,
                        symbols = pull.symbols,
                        newBalance = r.newBalance
                    )
                } else {
                    SpinOutcome.Lose(stake = stake, symbols = pull.symbols, newBalance = r.newBalance)
                }
            }
        }
    }
}
