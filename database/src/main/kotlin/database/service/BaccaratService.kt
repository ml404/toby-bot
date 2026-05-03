package database.service

import common.casino.CasinoCommonFailure
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

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            PlayOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            PlayOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            PlayOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : PlayOutcome, CasinoCommonFailure.UnknownUser
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
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, Baccarat.MIN_STAKE, Baccarat.MAX_STAKE, autoTopUp
        )) {
            is TopUpResolution.InvalidStake -> return PlayOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return PlayOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return PlayOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return PlayOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val hand = baccarat.play(side, Deck(random))
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, hand.multiplier)
        return when {
            hand.isWin -> {
                val jackpot = JackpotHelper.rollOnWin(
                    jackpotService, configService, userService, resolved.user, guildId, random
                )
                PlayOutcome.Win(
                    stake = stake,
                    payout = wager.payout,
                    net = wager.net,
                    side = side,
                    winner = hand.winner,
                    playerCards = hand.playerCards,
                    bankerCards = hand.bankerCards,
                    playerTotal = hand.playerTotal,
                    bankerTotal = hand.bankerTotal,
                    isPlayerNatural = hand.isPlayerNatural,
                    isBankerNatural = hand.isBankerNatural,
                    multiplier = hand.multiplier,
                    newBalance = wager.newBalance + jackpot,
                    jackpotPayout = jackpot,
                    soldTobyCoins = resolved.soldCoins,
                    newPrice = resolved.newPrice
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
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice
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
                    newBalance = wager.newBalance,
                    soldTobyCoins = resolved.soldCoins,
                    newPrice = resolved.newPrice,
                    lossTribute = tribute
                )
            }
        }
    }
}
