package database.service

import database.dto.ConfigDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Shared wager primitives for head-to-head PvP mini-games (currently
 * `/rps` and `/tictactoe`; future `/connect4`). Holds the bits that
 * are byte-identical between the per-game services:
 *
 *  - [preflightStart] — stake-bounds + self-challenge + balance checks
 *  - [debitBoth] — ascending-id lock + atomic stake debit on accept
 *  - [payWinner] — winner pay-out (pot − jackpot tribute) + XP grant.
 *    Handles both stake-bearing matches (locks, computes, updates
 *    balances) and free play (XP only).
 *  - [refundBoth] — symmetric stake refund (used on draws / double-
 *    no-pick). Free-play short-circuits with zeros.
 *  - [readStakeBounds] — per-guild config-driven min/max lookup
 *
 * Per-game services keep their own `ResolveOutcome` shape (RPS has
 * hidden-picks + draw branches; TTT has board-driven win + draw +
 * forfeit branches) but delegate the wager arithmetic here so a fix
 * to e.g. the tribute deduction or the lock-order race lands in one
 * place. Has **no JDA dependency by design** — same Spring-cycle
 * lesson the per-game services follow (PR #551).
 */
@Service
@Transactional
class PvpWagerService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val xpAwardService: XpAwardService,
) {

    /** Result of [preflightStart] — what the slash command sees before posting the offer. */
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

    /** Result of [debitBoth] — what the accept-button click sees. */
    sealed interface AcceptOutcome {
        /** Both balances debited; match is now LIVE. */
        data class Ok(val initiatorNewBalance: Long, val opponentNewBalance: Long) : AcceptOutcome
        data class InitiatorInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data object UnknownInitiator : AcceptOutcome
        data object UnknownOpponent : AcceptOutcome
    }

    /** Numbers from [payWinner]; the per-game service wraps them into its own Win variant. */
    data class PayResult(
        val winnerNewBalance: Long,
        val loserNewBalance: Long,
        val pot: Long,
        val lossTribute: Long,
        val xpGranted: Long,
    )

    /** Numbers from [refundBoth]; the per-game service wraps them into its own Draw variant. */
    data class RefundResult(
        val initiatorNewBalance: Long,
        val opponentNewBalance: Long,
    )

    /**
     * Pre-flight check the slash command runs before posting the
     * challenge. Does NOT debit anyone. The opponent-is-a-bot rejection
     * stays in the command (it has the JDA `User` and can read `isBot`
     * cheaply); this method only handles the self-challenge variant of
     * [StartOutcome.InvalidOpponent].
     */
    fun preflightStart(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
        minStake: Long,
        maxStake: Long,
    ): StartOutcome {
        if (stake < minStake || stake > maxStake) {
            return StartOutcome.InvalidStake(minStake, maxStake)
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

    /**
     * Debit `stake` from both players atomically (ascending-id lock so
     * concurrent matches involving the same user pair can't deadlock).
     * `stake == 0L` short-circuits past the lock and the user-table
     * write entirely — free-play accepts are pure ceremony.
     */
    fun debitBoth(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): AcceptOutcome {
        if (stake <= 0L) {
            val initiator = userService.getUserById(initiatorDiscordId, guildId)
                ?: return AcceptOutcome.UnknownInitiator
            val opponent = userService.getUserById(opponentDiscordId, guildId)
                ?: return AcceptOutcome.UnknownOpponent
            return AcceptOutcome.Ok(
                initiatorNewBalance = initiator.socialCredit ?: 0L,
                opponentNewBalance = opponent.socialCredit ?: 0L,
            )
        }
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId), guildId
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
        initiator.socialCredit = initiatorBalance - stake
        opponent.socialCredit = opponentBalance - stake
        userService.updateUser(initiator)
        userService.updateUser(opponent)
        return AcceptOutcome.Ok(
            initiatorNewBalance = initiator.socialCredit ?: 0L,
            opponentNewBalance = opponent.socialCredit ?: 0L,
        )
    }

    /**
     * Pay the winner the pot minus jackpot tribute and grant them XP.
     * Both players' stakes were debited at accept time so only the
     * winner's row needs writing. Re-acquires the lock under the
     * existing transaction (Postgres permits same-tx re-lock).
     *
     * `stake == 0L` is the free-play path: only the XP grant happens
     * and the wager numbers return as zeros. Callers don't branch.
     *
     * Returns `null` on the unreachable defensive case where one of
     * the players' rows is missing — the per-game service translates
     * that into its own `Unknown*` outcome.
     *
     * `xpReason` is the daily-cap-respecting tag passed to
     * [XpAwardService.award] (e.g. `"rps:win"`, `"tictactoe:win"`).
     */
    fun payWinner(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        stake: Long,
        guildId: Long,
        xpReason: String,
        xpAmount: Long = DEFAULT_WIN_XP,
    ): PayResult? {
        if (stake <= 0L) {
            val xp = xpAwardService.award(
                discordId = winnerDiscordId, guildId = guildId,
                amount = xpAmount, reason = xpReason,
            )
            return PayResult(
                winnerNewBalance = 0L, loserNewBalance = 0L,
                pot = 0L, lossTribute = 0L, xpGranted = xp,
            )
        }
        val locked = userService.lockUsersInAscendingOrder(
            listOf(winnerDiscordId, loserDiscordId), guildId
        )
        val winner = locked[winnerDiscordId] ?: return null
        val loser = locked[loserDiscordId] ?: return null

        val winnerStartBalance = winner.socialCredit ?: 0L
        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
        val pot = 2L * stake
        winner.socialCredit = winnerStartBalance + (pot - tribute)
        userService.updateUser(winner)
        val xp = xpAwardService.award(
            discordId = winnerDiscordId, guildId = guildId,
            amount = xpAmount, reason = xpReason,
        )
        return PayResult(
            winnerNewBalance = winner.socialCredit ?: 0L,
            loserNewBalance = loser.socialCredit ?: 0L,
            pot = pot,
            lossTribute = tribute,
            xpGranted = xp,
        )
    }

    /**
     * Refund `stake` to both players atomically (ascending-id lock).
     * Used on draws / double-no-pick. `stake <= 0L` returns the
     * free-play zeros and writes nothing.
     */
    fun refundBoth(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        guildId: Long,
    ): RefundResult {
        if (stake <= 0L) return RefundResult(0L, 0L)
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId), guildId
        )
        val initiator = locked[initiatorDiscordId] ?: return RefundResult(0L, 0L)
        val opponent = locked[opponentDiscordId] ?: return RefundResult(0L, 0L)
        initiator.socialCredit = (initiator.socialCredit ?: 0L) + stake
        opponent.socialCredit = (opponent.socialCredit ?: 0L) + stake
        userService.updateUser(initiator)
        userService.updateUser(opponent)
        return RefundResult(
            initiatorNewBalance = initiator.socialCredit ?: 0L,
            opponentNewBalance = opponent.socialCredit ?: 0L,
        )
    }

    /** Per-guild stake bounds for a wager game, with sane defaults. */
    fun readStakeBounds(
        guildId: Long,
        minKey: ConfigDto.Configurations,
        maxKey: ConfigDto.Configurations,
        defaultMin: Long,
        defaultMax: Long,
    ): Pair<Long, Long> {
        val min = configService.cfgLong(minKey, guildId, default = defaultMin, min = 0L)
        val max = configService.cfgLongMax(maxKey, guildId, default = defaultMax, min = min)
        return min to max
    }

    companion object {
        /** Default XP grant for a wager-game win. Per-game services can override via [payWinner]. */
        const val DEFAULT_WIN_XP: Long = 10L
    }
}
