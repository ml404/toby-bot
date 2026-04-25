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
        if (stake < Highlow.MIN_STAKE || stake > Highlow.MAX_STAKE) {
            return PlayOutcome.InvalidStake(Highlow.MIN_STAKE, Highlow.MAX_STAKE)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return PlayOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return PlayOutcome.InsufficientCredits(stake, balance)

        val hand = highlow.play(direction, random)
        val payout = hand.multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        val newBalance = user.socialCredit ?: 0L

        return if (hand.isWin) {
            PlayOutcome.Win(
                stake = stake,
                payout = payout,
                net = net,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                newBalance = newBalance
            )
        } else {
            PlayOutcome.Lose(
                stake = stake,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                newBalance = newBalance
            )
        }
    }
}
