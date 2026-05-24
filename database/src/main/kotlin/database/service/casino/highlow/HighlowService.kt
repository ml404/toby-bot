package database.service.casino.highlow

import common.casino.CasinoCommonFailure
import common.events.HighlowHandResolvedEvent
import database.dto.ConfigDto
import common.economy.Highlow
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random
import database.service.guild.ConfigService
import database.service.economy.EconomyTradeService
import database.service.economy.JackpotHelper
import database.service.economy.JackpotService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserService
import database.service.pvp.WagerHelper
import database.service.economy.JackpotGame
import database.service.pvp.TopUpResolution
import database.service.guild.cfgLong
import database.service.guild.cfgLongMax

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
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
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
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
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

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            PlayOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            PlayOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            PlayOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : PlayOutcome, CasinoCommonFailure.UnknownUser
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
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.HIGHLOW_MIN_STAKE, guildId, default = Highlow.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.HIGHLOW_MAX_STAKE, guildId, default = Highlow.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
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
        // Achievement: emit on every terminal hand. The handler increments
        // the streak counter on wins and resets it on losses — using the
        // existing achievement_progress row as the streak store so no DB
        // schema change is needed.
        eventPublisher?.publishEvent(
            HighlowHandResolvedEvent(discordId = discordId, guildId = guildId, isWin = hand.isWin)
        )
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, hand.multiplier)
        return if (hand.isWin) {
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.HIGHLOW, random,
            )
            PlayOutcome.Win(
                stake = stake,
                payout = wager.payout,
                net = wager.net,
                anchor = hand.anchor,
                next = hand.next,
                direction = hand.direction,
                multiplier = hand.multiplier,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
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
