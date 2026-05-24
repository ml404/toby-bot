package database.service.casino.horseracing

import common.casino.CasinoCommonFailure
import common.events.HorseRacingWonEvent
import database.dto.ConfigDto
import common.economy.HorseRacing
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
 * Atomic race path for the `/horse-racing` minigame. Both the Discord
 * `/horse-racing` command and the web `/casino/{guildId}/horse-racing`
 * page call through here so the debit/credit maths and the random
 * finishing-order draw only live in one place.
 *
 * Pattern mirrors [RouletteService] / [HighlowService]: lock the user
 * row via [UserService.getUserByIdForUpdate], validate inputs against
 * the live balance, run the race, mutate, persist — all inside a single
 * `@Transactional` boundary so concurrent calls from the same user
 * (Discord + web at once, or just spam-clicking) can't double-spend the
 * stake.
 *
 * Wins also roll [JackpotHelper] for a chance to bank the per-guild
 * jackpot pool; losses divert a tribute fraction into it (the same
 * shared pool that every casino game feeds into and pays out from).
 *
 * Payouts are fractional (e.g. 1.7× Place on the favourite), so this
 * service uses the `Double` overload of [WagerHelper.applyMultiplier] —
 * the same path Highlow takes.
 */
@Service
@Transactional
class HorseRacingService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val horseRacing: HorseRacing = HorseRacing(),
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    sealed interface RaceOutcome {
        data class Win(
            val stake: Long,
            val bet: HorseRacing.Bet,
            val pickedHorse: Int,
            val finishingOrder: List<Int>,
            val multiplier: Double,
            val payout: Long,
            val net: Long,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : RaceOutcome

        data class Lose(
            val stake: Long,
            val bet: HorseRacing.Bet,
            val pickedHorse: Int,
            val finishingOrder: List<Int>,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : RaceOutcome

        data class InvalidHorse(val min: Int, val max: Int) : RaceOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            RaceOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            RaceOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            RaceOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : RaceOutcome, CasinoCommonFailure.UnknownUser
    }

    fun race(
        discordId: Long,
        guildId: Long,
        stake: Long,
        pickedHorse: Int,
        bet: HorseRacing.Bet,
        autoTopUp: Boolean = false,
    ): RaceOutcome {
        if (pickedHorse !in 1..HorseRacing.FIELD_SIZE) {
            return RaceOutcome.InvalidHorse(1, HorseRacing.FIELD_SIZE)
        }

        val minStake = configService.cfgLong(
            ConfigDto.Configurations.HORSE_RACING_MIN_STAKE, guildId,
            default = HorseRacing.MIN_STAKE, min = 1L,
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.HORSE_RACING_MAX_STAKE, guildId,
            default = HorseRacing.MAX_STAKE, min = minStake,
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return RaceOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return RaceOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return RaceOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return RaceOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val race = horseRacing.race(pickedHorse, bet, random)
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, race.multiplier)
        return if (race.isWin) {
            eventPublisher?.publishEvent(HorseRacingWonEvent(discordId = discordId, guildId = guildId))
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.HORSE_RACING, random,
            )
            RaceOutcome.Win(
                stake = stake,
                bet = bet,
                pickedHorse = pickedHorse,
                finishingOrder = race.finishingOrder,
                multiplier = race.multiplier,
                payout = wager.payout,
                net = wager.net,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            RaceOutcome.Lose(
                stake = stake,
                bet = bet,
                pickedHorse = pickedHorse,
                finishingOrder = race.finishingOrder,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}
