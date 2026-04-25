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
    private val jackpotService: JackpotService,
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
            val newBalance: Long,
            val jackpotPayout: Long = 0L
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
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, ScratchCard.MIN_STAKE, ScratchCard.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> ScratchOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> ScratchOutcome.UnknownUser
            is BalanceCheck.Insufficient -> ScratchOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val result = card.scratch(random)
                val r = WagerHelper.applyMultiplier(userService, check.user, check.balance, stake, result.multiplier)
                if (result.isWin && result.winningSymbol != null) {
                    val jackpot = JackpotHelper.rollOnWin(jackpotService, userService, check.user, guildId, random)
                    ScratchOutcome.Win(
                        stake = stake,
                        payout = r.payout,
                        net = r.net,
                        cells = result.cells,
                        winningSymbol = result.winningSymbol,
                        matchCount = result.matchCount,
                        newBalance = r.newBalance + jackpot,
                        jackpotPayout = jackpot
                    )
                } else {
                    ScratchOutcome.Lose(stake = stake, cells = result.cells, newBalance = r.newBalance)
                }
            }
        }
    }
}
