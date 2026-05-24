package database.service.casino.slots

import common.casino.CasinoCommonFailure
import common.events.SlotsJackpotEvent
import database.dto.guild.ConfigDto
import common.economy.SlotMachine
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random
import database.service.casino.CasinoEdgeService
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
 * Atomic spin path for the `/slots` minigame. Both the Discord `/slots`
 * command and the web `/casino/{guildId}/slots` page call through here so
 * the debit/credit maths and the random draw only live in one place.
 *
 * Pattern mirrors [EconomyTradeService]: lock the user row first
 * ([UserService.getUserByIdForUpdate]), validate inputs against the live
 * balance, mutate, persist. The whole thing runs inside one
 * `@Transactional` boundary so concurrent `/slots` invocations from the
 * same user (Discord + web at once, or just spam-clicking) can't double-
 * spend the stake.
 *
 * Wins also roll [JackpotHelper] for a chance to bank the per-guild
 * jackpot pool (fed by trade fees in [EconomyTradeService]).
 *
 * When the caller passes `autoTopUp=true` and the player's credits
 * fall short of the stake, [CasinoTopUpHelper] sells just enough TOBY
 * to cover (mirroring TitlesWebService's "Buy with TOBY" flow). The
 * resulting trade row is tagged `CASINO_TOPUP` so the chart marker
 * can show why the sale happened.
 */
@Service
@Transactional
class SlotsService(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val tradeService: EconomyTradeService,
    private val marketService: TobyCoinMarketService,
    private val configService: ConfigService,
    private val casinoEdgeService: CasinoEdgeService,
    private val machine: SlotMachine = SlotMachine(),
    private val random: Random = Random.Default,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    sealed interface SpinOutcome {
        data class Win(
            val stake: Long,
            val multiplier: Long,
            val payout: Long,
            val net: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long,
            val jackpotPayout: Long = 0L,
            val jackpotTierIndex: Int = -1,
            val jackpotTierPayoutPct: Double = 0.0,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
        ) : SpinOutcome

        data class Lose(
            val stake: Long,
            val symbols: List<SlotMachine.Symbol>,
            val newBalance: Long,
            val soldTobyCoins: Long = 0L,
            val newPrice: Double? = null,
            val lossTribute: Long = 0L,
        ) : SpinOutcome

        data class InsufficientCredits(override val stake: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCredits
        data class InsufficientCoinsForTopUp(override val needed: Long, override val have: Long) :
            SpinOutcome, CasinoCommonFailure.InsufficientCoinsForTopUp
        data class InvalidStake(override val min: Long, override val max: Long) :
            SpinOutcome, CasinoCommonFailure.InvalidStake
        data object UnknownUser : SpinOutcome, CasinoCommonFailure.UnknownUser
    }

    fun spin(
        discordId: Long,
        guildId: Long,
        stake: Long,
        autoTopUp: Boolean = false,
        clickX: Int? = null,
        clickY: Int? = null,
        mouseMoved: Boolean? = null,
    ): SpinOutcome {
        val minStake = configService.cfgLong(
            ConfigDto.Configurations.SLOTS_MIN_STAKE, guildId, default = SlotMachine.MIN_STAKE, min = 1L
        )
        val maxStake = configService.cfgLongMax(
            ConfigDto.Configurations.SLOTS_MAX_STAKE, guildId, default = SlotMachine.MAX_STAKE, min = minStake
        )
        val resolved = when (val r = WagerHelper.checkLockOrTopUp(
            userService, tradeService, marketService,
            discordId, guildId, stake, minStake, maxStake, autoTopUp,
        )) {
            is TopUpResolution.InvalidStake -> return SpinOutcome.InvalidStake(r.min, r.max)
            TopUpResolution.UnknownUser -> return SpinOutcome.UnknownUser
            is TopUpResolution.StillInsufficientCredits ->
                return SpinOutcome.InsufficientCredits(r.stake, r.have)
            is TopUpResolution.InsufficientCoinsForTopUp ->
                return SpinOutcome.InsufficientCoinsForTopUp(r.needed, r.have)
            is TopUpResolution.Ok -> r
        }

        val fairPull = machine.pull(random)
        // Anti-autoclicker substitution: replace the fair pull with a
        // forced losing reel set. We pick three distinct symbols so the
        // animated reels still settle on something natural-looking
        // (any non-3-of-a-kind reads as a loss to the player).
        val pull = casinoEdgeService.applyBotEdge(
            discordId = discordId,
            guildId = guildId,
            gameKey = "slots",
            clickX = clickX, clickY = clickY, mouseMoved = mouseMoved,
            edgeMaxConfig = ConfigDto.Configurations.SLOTS_BOT_EDGE_MAX_PCT,
            fairOutcome = fairPull,
            asLoss = {
                // Three distinct symbols → guaranteed multiplier=0 in
                // SlotMachine.pull's logic (only 3-of-a-kind pays).
                val faces = SlotMachine.Symbol.entries.take(3)
                SlotMachine.Pull(symbols = faces, multiplier = 0L)
            },
        )
        val wager = WagerHelper.applyMultiplier(userService, resolved.user, resolved.balance, stake, pull.multiplier)
        return if (pull.isWin) {
            // 100× is the STAR three-of-a-kind jackpot tier (see SlotMachine
            // payouts). Lower multipliers (CHERRY/LEMON/BELL trios) are
            // ordinary wins and don't fire the achievement event.
            if (pull.multiplier == SlotMachine.JACKPOT_MULTIPLIER) {
                eventPublisher?.publishEvent(SlotsJackpotEvent(discordId = discordId, guildId = guildId))
            }
            val jackpot = JackpotHelper.rollOnWin(
                jackpotService, configService, userService, resolved.user, guildId,
                stake, JackpotGame.SLOTS, random,
            )
            SpinOutcome.Win(
                stake = stake,
                multiplier = pull.multiplier,
                payout = wager.payout,
                net = wager.net,
                symbols = pull.symbols,
                newBalance = wager.newBalance + jackpot.amount,
                jackpotPayout = jackpot.amount,
                jackpotTierIndex = jackpot.tierIndex,
                jackpotTierPayoutPct = jackpot.tierPayoutPct,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
            )
        } else {
            val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
            SpinOutcome.Lose(
                stake = stake,
                symbols = pull.symbols,
                newBalance = wager.newBalance,
                soldTobyCoins = resolved.soldCoins,
                newPrice = resolved.newPrice,
                lossTribute = tribute,
            )
        }
    }
}
