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
 *
 * Web callers can request `autoTopUp` to sell TOBY for the credit
 * shortfall via [CasinoTopUpHelper]; the resulting trade row is tagged
 * `CASINO_TOPUP`.
 */
@Service
@Transactional
class HighlowService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
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
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : PlayOutcome

        data class Lose(
            val stake: Long,
            val anchor: Int,
            val next: Int,
            val direction: Highlow.Direction,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : PlayOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : PlayOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : PlayOutcome
        data class InvalidStake(val min: Long, val max: Long) : PlayOutcome
        data object UnknownUser : PlayOutcome
    }

    /** Draw a fresh anchor without committing any state. The web flow uses this on page load. */
    fun dealAnchor(): Int = highlow.dealAnchor(random)

    /** Bundled flow: server draws both cards inside this call. Discord uses this. */
    fun play(discordId: Long, guildId: Long, stake: Long, direction: Highlow.Direction): PlayOutcome =
        playInternal(discordId, guildId, stake, direction, anchor = null, autoTopUp = false)

    /**
     * Stepwise flow: caller already revealed [anchor] to the player.
     * The next card is drawn here and the wager settles atomically.
     */
    fun play(
        discordId: Long,
        guildId: Long,
        stake: Long,
        direction: Highlow.Direction,
        anchor: Int,
        autoTopUp: Boolean = false
    ): PlayOutcome = playInternal(discordId, guildId, stake, direction, anchor = anchor, autoTopUp = autoTopUp)

    private fun playInternal(
        discordId: Long,
        guildId: Long,
        stake: Long,
        direction: Highlow.Direction,
        anchor: Int?,
        autoTopUp: Boolean
    ): PlayOutcome {
        val initial = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Highlow.MIN_STAKE, Highlow.MAX_STAKE
        )
        var soldCoins = 0L
        var soldNewPrice: Double? = null
        val resolved = when (initial) {
            is BalanceCheck.InvalidStake -> return PlayOutcome.InvalidStake(initial.min, initial.max)
            BalanceCheck.UnknownUser -> return PlayOutcome.UnknownUser
            is BalanceCheck.Insufficient -> {
                if (!autoTopUp) return PlayOutcome.InsufficientCredits(initial.stake, initial.have)
                val user = userService.getUserByIdForUpdate(discordId, guildId)
                    ?: return PlayOutcome.UnknownUser
                val topUp = CasinoTopUpHelper.ensureCreditsForWager(
                    tradeService, marketService, userService,
                    user, guildId, currentBalance = initial.have, stake = stake
                )
                when (topUp) {
                    is TopUpResult.InsufficientCoins ->
                        return PlayOutcome.InsufficientCoinsForTopUp(topUp.needed, topUp.have)
                    TopUpResult.MarketUnavailable ->
                        return PlayOutcome.InsufficientCredits(initial.stake, initial.have)
                    is TopUpResult.ToppedUp -> {
                        soldCoins = topUp.soldCoins
                        soldNewPrice = topUp.newPrice
                        BalanceCheck.Ok(topUp.user, topUp.balance)
                    }
                }
            }
            is BalanceCheck.Ok -> initial
        }

        val hand = if (anchor != null) {
            highlow.resolve(anchor, direction, random)
        } else {
            highlow.play(direction, random)
        }
        val r = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, hand.multiplier)
        return if (hand.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, userService, resolved.user, guildId, random)
            PlayOutcome.Win(
                stake = stake,
                payout = r.payout,
                net = r.net,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                newBalance = r.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = soldCoins,
                newPrice = soldNewPrice
            )
        } else {
            PlayOutcome.Lose(
                stake = stake,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                newBalance = r.newBalance,
                soldTobyCoins = soldCoins,
                newPrice = soldNewPrice
            )
        }
    }
}
