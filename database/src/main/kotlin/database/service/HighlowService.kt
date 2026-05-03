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
    private val configService: ConfigService,
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
            val multiplier: Double,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : PlayOutcome

        data class Lose(
            val stake: Long,
            val anchor: Int,
            val next: Int,
            val direction: Highlow.Direction,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : PlayOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : PlayOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : PlayOutcome
        data class InvalidStake(val min: Long, val max: Long) : PlayOutcome
        data object UnknownUser : PlayOutcome
    }

    /** Draw a fresh anchor without committing any state. The web flow uses this on page load. */
    fun dealAnchor(): Int = highlow.dealAnchor(random)

    /**
     * Per-anchor payout multiplier for [direction]. Surfaced so the web
     * page and the Discord embed can label each direction button with
     * what it actually pays before the player commits.
     */
    fun payoutMultiplier(anchor: Int, direction: Highlow.Direction): Double =
        highlow.payoutMultiplier(anchor, direction)

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
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, Highlow.MIN_STAKE, Highlow.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return PlayOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return PlayOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return PlayOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return PlayOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val hand = if (anchor != null) {
            highlow.resolve(anchor, direction, random)
        } else {
            highlow.play(direction, random)
        }
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, hand.multiplier)
        return if (hand.isWin) {
            val jackpot = JackpotHelper.rollOnWin(jackpotService, configService, userService, resolved.user, guildId, random)
            PlayOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                multiplier = hand.multiplier,
                newBalance = wager.newBalance + jackpot,
                jackpotPayout = jackpot,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            PlayOutcome.Lose(
                stake = stake,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}
