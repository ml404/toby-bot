package database.service

import database.economy.Coinflip
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic flip path for the `/coinflip` minigame. Both Discord
 * `/coinflip` and the web `/casino/{guildId}/coinflip` page call through
 * here, mirroring the [SlotsService] pattern: lock the user row first
 * via [UserService.getUserByIdForUpdate], validate inputs, mutate,
 * persist, all inside a single `@Transactional` boundary.
 */
@Service
@Transactional
class CoinflipService(
    private val userService: UserService,
    private val coinflip: Coinflip = Coinflip(),
    private val random: Random = Random.Default
) {

    sealed interface FlipOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long
        ) : FlipOutcome

        data class Lose(
            val stake: Long,
            val landed: Coinflip.Side,
            val predicted: Coinflip.Side,
            val newBalance: Long
        ) : FlipOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : FlipOutcome
        data class InvalidStake(val min: Long, val max: Long) : FlipOutcome
        data object UnknownUser : FlipOutcome
    }

    fun flip(discordId: Long, guildId: Long, stake: Long, predicted: Coinflip.Side): FlipOutcome {
        if (stake < Coinflip.MIN_STAKE || stake > Coinflip.MAX_STAKE) {
            return FlipOutcome.InvalidStake(Coinflip.MIN_STAKE, Coinflip.MAX_STAKE)
        }
        val user = userService.getUserByIdForUpdate(discordId, guildId)
            ?: return FlipOutcome.UnknownUser
        val balance = user.socialCredit ?: 0L
        if (balance < stake) return FlipOutcome.InsufficientCredits(stake, balance)

        val flip = coinflip.flip(predicted, random)
        // payout = multiplier × stake on a match, 0 on a miss. Net change
        // to the user's balance is (payout - stake): positive on wins, -stake
        // on losses.
        val payout = flip.multiplier * stake
        val net = payout - stake
        user.socialCredit = balance + net
        userService.updateUser(user)
        val newBalance = user.socialCredit ?: 0L

        return if (flip.isWin) {
            FlipOutcome.Win(
                stake = stake,
                payout = payout,
                net = net,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = newBalance
            )
        } else {
            FlipOutcome.Lose(
                stake = stake,
                landed = flip.landed,
                predicted = flip.predicted,
                newBalance = newBalance
            )
        }
    }
}
