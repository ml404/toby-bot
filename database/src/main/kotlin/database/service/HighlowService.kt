package database.service

import database.economy.Highlow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic play path for the `/highlow` minigame. Same lock-then-mutate
 * pattern as the other minigame services via
 * [UserService.getUserByIdForUpdate].
 *
 * Two entry points:
 *   - [play] without an anchor — Discord and any caller that wants the
 *     bundled "draw both cards now" semantics.
 *   - [play] with an anchor — the web caller has already revealed the
 *     anchor to the player; this resolves the next card against that
 *     anchor inside the same transaction as the wager.
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

    /** Draw a fresh anchor without committing any state. The web flow uses this on page load. */
    fun dealAnchor(): Int = highlow.dealAnchor(random)

    /** Bundled flow: server draws both cards inside this call. */
    fun play(discordId: Long, guildId: Long, stake: Long, direction: Highlow.Direction): PlayOutcome =
        playInternal(discordId, guildId, stake, direction, anchor = null)

    /**
     * Stepwise flow: caller already revealed [anchor] to the player.
     * The next card is drawn here and the wager settles atomically.
     */
    fun play(
        discordId: Long,
        guildId: Long,
        stake: Long,
        direction: Highlow.Direction,
        anchor: Int
    ): PlayOutcome = playInternal(discordId, guildId, stake, direction, anchor = anchor)

    private fun playInternal(
        discordId: Long,
        guildId: Long,
        stake: Long,
        direction: Highlow.Direction,
        anchor: Int?
    ): PlayOutcome {
        return when (val check = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Highlow.MIN_STAKE, Highlow.MAX_STAKE
        )) {
            is BalanceCheck.InvalidStake -> PlayOutcome.InvalidStake(check.min, check.max)
            BalanceCheck.UnknownUser -> PlayOutcome.UnknownUser
            is BalanceCheck.Insufficient -> PlayOutcome.InsufficientCredits(check.stake, check.have)
            is BalanceCheck.Ok -> {
                val hand = if (anchor != null) {
                    highlow.resolve(anchor, direction, random)
                } else {
                    highlow.play(direction, random)
                }
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
