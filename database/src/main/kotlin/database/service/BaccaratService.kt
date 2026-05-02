package database.service

import database.card.Card
import database.card.Deck
import database.economy.Baccarat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Atomic play path for the `/baccarat` minigame. Same lock-then-mutate
 * pattern as the other card-flavoured wager service ([HighlowService])
 * via [UserService.getUserByIdForUpdate]:
 *   - validate stake against [Baccarat.MIN_STAKE]..[Baccarat.MAX_STAKE]
 *   - lock the user row
 *   - confirm balance ≥ stake (optionally sell TOBY for the shortfall
 *     via [CasinoTopUpHelper] when [autoTopUp] is set)
 *   - deal both hands from a fresh [Deck] and resolve the side bet
 *   - apply the resulting fractional multiplier through
 *     [WagerHelper.applyMultiplier]
 *   - feed [JackpotHelper] on win/loss (push is jackpot-neutral — the
 *     stake is refunded so there's nothing to roll on or tribute)
 */
@Service
@Transactional
class BaccaratService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val baccarat: Baccarat = Baccarat(),
    private val random: Random = Random.Default
) {

    sealed interface PlayOutcome {
        data class Win(
            val stake: Long,
            val payout: Long,
            val net: Long,
            val side: Baccarat.Side,
            val winner: Baccarat.Side,
            val playerCards: List<Card>,
            val bankerCards: List<Card>,
            val playerTotal: Int,
            val bankerTotal: Int,
            val isPlayerNatural: Boolean,
            val isBankerNatural: Boolean,
            val multiplier: Double,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : PlayOutcome

        data class Push(
            val stake: Long,
            val side: Baccarat.Side,
            val playerCards: List<Card>,
            val bankerCards: List<Card>,
            val playerTotal: Int,
            val bankerTotal: Int,
            val isPlayerNatural: Boolean,
            val isBankerNatural: Boolean,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null
        ) : PlayOutcome

        data class Lose(
            val stake: Long,
            val side: Baccarat.Side,
            val winner: Baccarat.Side,
            val playerCards: List<Card>,
            val bankerCards: List<Card>,
            val playerTotal: Int,
            val bankerTotal: Int,
            val isPlayerNatural: Boolean,
            val isBankerNatural: Boolean,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L
        ) : PlayOutcome

        data class InsufficientCredits(val stake: Long, val have: Long) : PlayOutcome
        data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : PlayOutcome
        data class InvalidStake(val min: Long, val max: Long) : PlayOutcome
        data object UnknownUser : PlayOutcome
    }

    /**
     * Per-side payout multiplier used by the prompt embed and button
     * labels so the player sees what each side pays before committing.
     */
    fun previewMultiplier(side: Baccarat.Side): Double = baccarat.previewMultiplier(side)

    fun play(
        discordId: Long,
        guildId: Long,
        stake: Long,
        side: Baccarat.Side,
        autoTopUp: Boolean = false
    ): PlayOutcome {
        val initial = WagerHelper.checkAndLock(
            userService, discordId, guildId, stake, Baccarat.MIN_STAKE, Baccarat.MAX_STAKE
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

        val hand = baccarat.play(side, Deck(random))
        val r = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, hand.multiplier)
        return when {
            hand.isWin -> {
                val jackpot = JackpotHelper.rollOnWin(
                    jackpotService, configService, userService, resolved.user, guildId, random
                )
                PlayOutcome.Win(
                    stake = stake,
                    payout = r.payout,
                    net = r.net,
                    side = side,
                    winner = hand.winner,
                    playerCards = hand.playerCards,
                    bankerCards = hand.bankerCards,
                    playerTotal = hand.playerTotal,
                    bankerTotal = hand.bankerTotal,
                    isPlayerNatural = hand.isPlayerNatural,
                    isBankerNatural = hand.isBankerNatural,
                    multiplier = hand.multiplier,
                    newBalance = r.newBalance + jackpot,
                    jackpotPayout = jackpot,
                    soldTobyCoins = soldCoins,
                    newPrice = soldNewPrice
                )
            }
            hand.isPush -> PlayOutcome.Push(
                stake = stake,
                side = side,
                playerCards = hand.playerCards,
                bankerCards = hand.bankerCards,
                playerTotal = hand.playerTotal,
                bankerTotal = hand.bankerTotal,
                isPlayerNatural = hand.isPlayerNatural,
                isBankerNatural = hand.isBankerNatural,
                newBalance = r.newBalance,
                soldTobyCoins = soldCoins,
                newPrice = soldNewPrice
            )
            else -> {
                val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
                PlayOutcome.Lose(
                    stake = stake,
                    side = side,
                    winner = hand.winner,
                    playerCards = hand.playerCards,
                    bankerCards = hand.bankerCards,
                    playerTotal = hand.playerTotal,
                    bankerTotal = hand.bankerTotal,
                    isPlayerNatural = hand.isPlayerNatural,
                    isBankerNatural = hand.isBankerNatural,
                    newBalance = r.newBalance,
                    soldTobyCoins = soldCoins,
                    newPrice = soldNewPrice,
                    lossTribute = tribute
                )
            }
        }
    }
}
