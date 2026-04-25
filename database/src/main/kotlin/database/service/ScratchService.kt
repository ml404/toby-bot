package database.service

import database.economy.ScratchCard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic scratch path for the `/scratch` minigame. Same lock-then-mutate
 * pattern as the other minigame services.
 */
@Service
@Transactional
class ScratchService(
    private val userService: UserService,
    private val card: ScratchCard = ScratchCard(),
    private val random: Random = Random.Default
) {

    sealed interface ScratchOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val cells: List<database.economy.SlotMachine.Symbol>,
            val winningSymbol: database.economy.SlotMachine.Symbol,
            val matchCount: Int,
            val newBalance: Long
        ) : ScratchOutcome

        data class Lose(
            val stake: Long,
            val cells: List<database.economy.SlotMachine.Symbol>,
            val newBalance: Long
        ) : ScratchOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : ScratchOutcome
        data class InvalidStake(val min: Long, val max: Long) : ScratchOutcome
        data object UnknownUser : ScratchOutcome
    }

    fun scratch(discordId: Long, guildId: Long, stake: Long): ScratchOutcome {
        if (stake < ScratchCard.MIN_STAKE || stake > ScratchCard.MAX_STAKE) {
            return ScratchOutcome.InvalidStake(ScratchCard.MIN_STAKE, ScratchCard.MAX_STAKE)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return ScratchOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return ScratchOutcome.InsufficientCredits(stake, balance)

        val result = card.scratch(random)
        val payout = result.multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        val newBalance = user.socialCredit ?: 0L

        return if (result.isWin && result.winningSymbol != null) {
            ScratchOutcome.Win(
                stake = stake,
                payout = payout,
                net = net,
                cells = result.cells,
                winningSymbol = result.winningSymbol,
                matchCount = result.matchCount,
                newBalance = newBalance
            )
        } else {
            ScratchOutcome.Lose(stake = stake, cells = result.cells, newBalance = newBalance)
        }
    }
}
