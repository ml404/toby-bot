package database.service

import database.economy.Highlow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic play path for the `/highlow` minigame. Same lock-then-mutate
 * pattern as the other minigame services via
 * [UserService.getUserByIdForUpdate].
 */
@Service
@Transactional
class HighlowService(
    private val userService: UserService,
    private val highlow: Highlow = Highlow(),
    private val random: Random = Random.Default
) {

    sealed interface PlayOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val anchor: Int,
            val next: Int,
            val direction: Highlow.Direction,
            val newBalance: Long
        ) : PlayOutcome

        data class Lose(
            val stake: Long,
            val anchor: Int,
            val next: Int,
            val direction: Highlow.Direction,
            val newBalance: Long
        ) : PlayOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : PlayOutcome
        data class InvalidStake(val min: Long, val max: Long) : PlayOutcome
        data object UnknownUser : PlayOutcome
    }

    fun play(discordId: Long, guildId: Long, stake: Long, direction: Highlow.Direction): PlayOutcome {
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Highlow.MIN_STAKE, Highlow.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> PlayOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> PlayOutcome.UnknownUser
            is BalanceCheck.Insufficient -> PlayOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val hand = highlow.play(direction, random)
                val r = WagerHelper.applyMultiplier(userService, check.user, check.balance, stake, hand.multiplier)
                if (hand.isWin) {
                    PlayOutcome.Win(
                        stake = stake,
                        payout = r.payout,
                        net = r.net,
                        anchor = hand.anchor,
                        next = hand.next,
                        direction = hand.direction,
                        newBalance = r.newBalance
                    )
                } else {
                    PlayOutcome.Lose(
                        stake = stake,
                        anchor = hand.anchor,
                        next = hand.next,
                        direction = hand.direction,
                        newBalance = r.newBalance
                    )
                }
            }
        }
    }
}
