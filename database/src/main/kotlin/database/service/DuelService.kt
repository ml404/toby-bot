package database.service

import database.dto.DuelLogDto
import database.economy.Coinflip
import database.persistence.DuelLogPersistence
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.random.Random

/**
 * Head-to-head 50/50 wager between two users. Two phases:
 *
 *   - [startDuel] — pre-flight balance check at offer time. Does NOT
 *     debit. Allows the bot/web layer to refuse a clearly-doomed offer
 *     up-front, but the authoritative check happens at accept time.
 *   - [acceptDuel] — atomic resolve. Locks both users in ascending
 *     discord-id order, re-verifies both balances, picks a winner
 *     by a fair coinflip, debits both and credits the winner the pot
 *     minus the jackpot tribute. Persists a [DuelLogDto] row.
 *
 * Loss tribute (10 % of stake by default, configurable per-guild via
 * `JACKPOT_LOSS_TRIBUTE_PCT`) is paid out of the pot before the winner
 * is credited — the winner receives `2*stake - tribute`. This keeps
 * the loser's accounting identical to a casino loss (`-stake`,
 * period) and routes a small bleed into the per-guild jackpot pool,
 * matching how `/slots`, `/dice`, etc. feed it.
 *
 * Pending offer state (the 60-second Accept/Decline window between a
 * `/duel` slash invocation and the opponent's click) is NOT persisted
 * here — see `database.duel.PendingDuelRegistry`, an in-memory
 * @Component shared across the whole Spring context (Discord bot and
 * web layer). Decline and timeout therefore produce no DB writes.
 */
@Service
@Transactional
class DuelService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val duelLogPersistence: DuelLogPersistence,
    private val random: Random = Random.Default,
) {
    sealed interface StartOutcome {
        data class Ok(val initiatorBalance: Long) : StartOutcome
        data class InvalidStake(val min: Long, val max: Long) : StartOutcome
        data class InvalidOpponent(val reason: Reason) : StartOutcome {
            enum class Reason { SELF, BOT }
        }
        data class InitiatorInsufficient(val have: Long, val needed: Long) : StartOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : StartOutcome
        data object UnknownInitiator : StartOutcome
        data object UnknownOpponent : StartOutcome
    }

    sealed interface AcceptOutcome {
        data class Win(
            val winnerDiscordId: Long,
            val loserDiscordId: Long,
            val stake: Long,
            val pot: Long,
            val winnerNewBalance: Long,
            val loserNewBalance: Long,
            val lossTribute: Long,
            val jackpotPool: Long = 0L
        ) : AcceptOutcome

        data class InitiatorInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data object UnknownInitiator : AcceptOutcome
        data object UnknownOpponent : AcceptOutcome
    }

    fun startDuel(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): StartOutcome {
        if (stake < MIN_STAKE || stake > MAX_STAKE) {
            return StartOutcome.InvalidStake(MIN_STAKE, MAX_STAKE)
        }
        if (initiatorDiscordId == opponentDiscordId) {
            return StartOutcome.InvalidOpponent(StartOutcome.InvalidOpponent.Reason.SELF)
        }

        val initiator = userService.getUserById(initiatorDiscordId, guildId)
            ?: return StartOutcome.UnknownInitiator
        val opponent = userService.getUserById(opponentDiscordId, guildId)
            ?: return StartOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        if (initiatorBalance < stake) {
            return StartOutcome.InitiatorInsufficient(initiatorBalance, stake)
        }
        val opponentBalance = opponent.socialCredit ?: 0L
        if (opponentBalance < stake) {
            return StartOutcome.OpponentInsufficient(opponentBalance, stake)
        }

        return StartOutcome.Ok(initiatorBalance)
    }

    fun acceptDuel(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
        at: Instant = Instant.now(),
    ): AcceptOutcome {
        // Lock both rows in deterministic ascending order to avoid deadlocks.
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId),
            guildId
        )
        val initiator = locked[initiatorDiscordId] ?: return AcceptOutcome.UnknownInitiator
        val opponent = locked[opponentDiscordId] ?: return AcceptOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        if (initiatorBalance < stake) {
            return AcceptOutcome.InitiatorInsufficient(initiatorBalance, stake)
        }
        val opponentBalance = opponent.socialCredit ?: 0L
        if (opponentBalance < stake) {
            return AcceptOutcome.OpponentInsufficient(opponentBalance, stake)
        }

        val initiatorWins = random.nextBoolean()
        val winner = if (initiatorWins) initiator else opponent
        val loser = if (initiatorWins) opponent else initiator
        val winnerStartBalance = if (initiatorWins) initiatorBalance else opponentBalance
        val loserStartBalance = if (initiatorWins) opponentBalance else initiatorBalance

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
        val pot = 2L * stake
        val winnerPayout = pot - tribute

        // Loser is debited their full stake. Winner gets back their stake plus
        // the loser's stake minus the tribute (the bleed into the jackpot).
        winner.socialCredit = winnerStartBalance - stake + winnerPayout
        loser.socialCredit = loserStartBalance - stake
        userService.updateUser(winner)
        userService.updateUser(loser)

        duelLogPersistence.insert(
            DuelLogDto(
                guildId = guildId,
                initiatorDiscordId = initiatorDiscordId,
                opponentDiscordId = opponentDiscordId,
                winnerDiscordId = winner.discordId,
                loserDiscordId = loser.discordId,
                stake = stake,
                pot = pot,
                lossTribute = tribute,
                resolvedAt = at
            )
        )

        return AcceptOutcome.Win(
            winnerDiscordId = winner.discordId,
            loserDiscordId = loser.discordId,
            stake = stake,
            pot = pot,
            winnerNewBalance = winner.socialCredit ?: 0L,
            loserNewBalance = loser.socialCredit ?: 0L,
            lossTribute = tribute,
            jackpotPool = jackpotService.getPool(guildId)
        )
    }

    companion object {
        const val MIN_STAKE: Long = Coinflip.MIN_STAKE
        const val MAX_STAKE: Long = 500L
    }
}
