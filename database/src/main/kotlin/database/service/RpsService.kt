package database.service

import database.dto.ConfigDto
import database.rps.RpsEngine
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Head-to-head Rock-Paper-Scissors wager between two users. Three
 * phases — mirrors `/duel`'s preflight + accept flow but adds a hidden-
 * pick stage between accept and resolution:
 *
 *   - [startMatch] — pre-flight balance / stake-bounds check. Does NOT
 *     debit. Lets the slash command refuse a doomed offer up-front.
 *   - [acceptMatch] — atomic debit. Locks both users in ascending
 *     discord-id order, re-verifies both balances, debits the stake
 *     from each. Picks are stored in the in-memory session registry
 *     (not this service) so this service is pure wager-math.
 *   - [resolveMatch] — credits the winner pot minus jackpot tribute,
 *     OR refunds on a draw / double-no-pick. Single entry point for
 *     both happy-path resolution and forfeit/timeout settlement so
 *     all the branching wager arithmetic lives in one place.
 *
 * Loss tribute is computed the same way as `/duel` (via [JackpotHelper])
 * — every wager game feeds the same per-guild jackpot pool so the
 * economy stays cohesive.
 *
 * Has **no JDA dependency by design** — that's the regression the
 * Profile cards Spring-cycle (PR #551) taught us. Services reachable
 * from a `Command` bean must not pull `JDA` from the container; let
 * the slash command pass `Guild` / `Member` through.
 */
@Service
@Transactional
class RpsService @Autowired constructor(
    private val userService: UserService,
    private val jackpotService: JackpotService,
    private val configService: ConfigService,
    private val xpAwardService: XpAwardService,
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
        /** Both balances debited; match is now LIVE. */
        data class Ok(val initiatorNewBalance: Long, val opponentNewBalance: Long) : AcceptOutcome
        data class InitiatorInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data class OpponentInsufficient(val have: Long, val needed: Long) : AcceptOutcome
        data object UnknownInitiator : AcceptOutcome
        data object UnknownOpponent : AcceptOutcome
    }

    /**
     * Per-resolution result. Wraps the human-readable picks alongside
     * the wager arithmetic so the slash command can render the embed
     * without re-deriving anything.
     */
    sealed interface ResolveOutcome {
        data class Win(
            val winnerDiscordId: Long,
            val loserDiscordId: Long,
            val winnerChoice: RpsEngine.Choice,
            val loserChoice: RpsEngine.Choice,
            val stake: Long,
            val pot: Long,
            val winnerNewBalance: Long,
            val loserNewBalance: Long,
            val lossTribute: Long,
            val xpGranted: Long,
        ) : ResolveOutcome

        /** Both picked the same move; both stakes refunded. */
        data class Draw(
            val choice: RpsEngine.Choice,
            val stake: Long,
            val initiatorNewBalance: Long,
            val opponentNewBalance: Long,
        ) : ResolveOutcome

        /** Pick timeout with no one having picked; double refund. */
        data class DoubleRefund(
            val stake: Long,
            val initiatorNewBalance: Long,
            val opponentNewBalance: Long,
        ) : ResolveOutcome

        data object UnknownInitiator : ResolveOutcome
        data object UnknownOpponent : ResolveOutcome
    }

    fun startMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): StartOutcome {
        val (minStake, maxStake) = readStakeBounds(guildId)
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

    fun acceptMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): AcceptOutcome {
        // No debit needed when there's no stake — `/rps` without a wager
        // is pure fun and should skip the user-table touch entirely.
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
     * Settle a match. Handles four cases:
     *   - both players picked + clean winner → win/loss accounting + XP
     *   - both players picked + identical move → draw, refund both
     *   - exactly one player picked → other forfeits, picker wins
     *   - neither player picked → double refund
     *
     * Idempotent against the user table only if called once per match —
     * the in-memory session registry is responsible for atomically
     * removing the session before this is called.
     */
    fun resolveMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
        initiatorChoice: RpsEngine.Choice?,
        opponentChoice: RpsEngine.Choice?,
    ): ResolveOutcome {
        // Stake-free play: no debits to settle. Award nominal XP to the
        // winner only (consistent with stake-bearing wins) and skip the
        // user-table touch otherwise.
        if (stake <= 0L) {
            return resolveFreePlay(
                initiatorDiscordId, opponentDiscordId, guildId,
                initiatorChoice, opponentChoice,
            )
        }
        val locked = userService.lockUsersInAscendingOrder(
            listOf(initiatorDiscordId, opponentDiscordId), guildId
        )
        val initiator = locked[initiatorDiscordId] ?: return ResolveOutcome.UnknownInitiator
        val opponent = locked[opponentDiscordId] ?: return ResolveOutcome.UnknownOpponent

        val initiatorBalance = initiator.socialCredit ?: 0L
        val opponentBalance = opponent.socialCredit ?: 0L

        // Case A: both picked.
        if (initiatorChoice != null && opponentChoice != null) {
            return when (val outcome = RpsEngine.resolve(initiatorChoice, opponentChoice)) {
                RpsEngine.Outcome.Draw -> {
                    // Refund both stakes.
                    initiator.socialCredit = initiatorBalance + stake
                    opponent.socialCredit = opponentBalance + stake
                    userService.updateUser(initiator)
                    userService.updateUser(opponent)
                    ResolveOutcome.Draw(
                        choice = initiatorChoice,
                        stake = stake,
                        initiatorNewBalance = initiator.socialCredit ?: 0L,
                        opponentNewBalance = opponent.socialCredit ?: 0L,
                    )
                }
                RpsEngine.Outcome.FirstWins -> payWinner(
                    winner = initiator, loser = opponent, stake = stake, guildId = guildId,
                    winnerChoice = initiatorChoice, loserChoice = opponentChoice,
                )
                RpsEngine.Outcome.SecondWins -> payWinner(
                    winner = opponent, loser = initiator, stake = stake, guildId = guildId,
                    winnerChoice = opponentChoice, loserChoice = initiatorChoice,
                )
            }
        }

        // Case B: exactly one picked. The picker wins by forfeit.
        if (initiatorChoice != null && opponentChoice == null) {
            return payWinner(
                winner = initiator, loser = opponent, stake = stake, guildId = guildId,
                winnerChoice = initiatorChoice, loserChoice = pickPlaceholder(initiatorChoice),
            )
        }
        if (opponentChoice != null && initiatorChoice == null) {
            return payWinner(
                winner = opponent, loser = initiator, stake = stake, guildId = guildId,
                winnerChoice = opponentChoice, loserChoice = pickPlaceholder(opponentChoice),
            )
        }

        // Case C: neither picked. Double refund.
        initiator.socialCredit = initiatorBalance + stake
        opponent.socialCredit = opponentBalance + stake
        userService.updateUser(initiator)
        userService.updateUser(opponent)
        return ResolveOutcome.DoubleRefund(
            stake = stake,
            initiatorNewBalance = initiator.socialCredit ?: 0L,
            opponentNewBalance = opponent.socialCredit ?: 0L,
        )
    }

    /**
     * Refund both players the stake — only used on PENDING-stage
     * timeout / decline when [acceptMatch] hasn't fired yet. Safe
     * no-op when stake is 0.
     *
     * (Kept separate from [resolveMatch] because at the pending stage
     * no one has been debited; resolveMatch's accounting assumes the
     * stake is already locked in the users' negative balance.)
     */
    fun refundPending(@Suppress("UNUSED_PARAMETER") initiatorDiscordId: Long, @Suppress("UNUSED_PARAMETER") opponentDiscordId: Long, @Suppress("UNUSED_PARAMETER") guildId: Long, @Suppress("UNUSED_PARAMETER") stake: Long) {
        // No-op by design — the pending stage never debits. Method
        // exists so callers can call it unconditionally and read like
        // the lifecycle is symmetric, the same way `cancel()` on the
        // pending duel registry produces no DB writes.
    }

    private fun payWinner(
        winner: database.dto.UserDto,
        loser: database.dto.UserDto,
        stake: Long,
        guildId: Long,
        winnerChoice: RpsEngine.Choice,
        loserChoice: RpsEngine.Choice,
    ): ResolveOutcome.Win {
        val winnerStartBalance = winner.socialCredit ?: 0L
        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake)
        val pot = 2L * stake
        val winnerPayout = pot - tribute
        // Both players were already debited at acceptMatch time. Winner
        // receives the pot minus tribute back; loser receives nothing.
        winner.socialCredit = winnerStartBalance + winnerPayout
        userService.updateUser(winner)
        // No loser write — their balance was already debited at accept.
        val xpGranted = xpAwardService.award(
            discordId = winner.discordId,
            guildId = guildId,
            amount = WIN_XP,
            reason = "rps:win",
        )
        return ResolveOutcome.Win(
            winnerDiscordId = winner.discordId,
            loserDiscordId = loser.discordId,
            winnerChoice = winnerChoice,
            loserChoice = loserChoice,
            stake = stake,
            pot = pot,
            winnerNewBalance = winner.socialCredit ?: 0L,
            loserNewBalance = loser.socialCredit ?: 0L,
            lossTribute = tribute,
            xpGranted = xpGranted,
        )
    }

    private fun resolveFreePlay(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        initiatorChoice: RpsEngine.Choice?,
        opponentChoice: RpsEngine.Choice?,
    ): ResolveOutcome {
        // Both picked → resolve by engine; one picked → other forfeits;
        // neither picked → draw-shaped DoubleRefund (no funds involved).
        if (initiatorChoice != null && opponentChoice != null) {
            return when (RpsEngine.resolve(initiatorChoice, opponentChoice)) {
                RpsEngine.Outcome.Draw -> ResolveOutcome.Draw(
                    choice = initiatorChoice, stake = 0L,
                    initiatorNewBalance = 0L, opponentNewBalance = 0L,
                )
                RpsEngine.Outcome.FirstWins -> freePlayWin(
                    initiatorDiscordId, opponentDiscordId, guildId,
                    winnerChoice = initiatorChoice, loserChoice = opponentChoice,
                )
                RpsEngine.Outcome.SecondWins -> freePlayWin(
                    opponentDiscordId, initiatorDiscordId, guildId,
                    winnerChoice = opponentChoice, loserChoice = initiatorChoice,
                )
            }
        }
        if (initiatorChoice != null) return freePlayWin(
            initiatorDiscordId, opponentDiscordId, guildId,
            winnerChoice = initiatorChoice, loserChoice = pickPlaceholder(initiatorChoice),
        )
        if (opponentChoice != null) return freePlayWin(
            opponentDiscordId, initiatorDiscordId, guildId,
            winnerChoice = opponentChoice, loserChoice = pickPlaceholder(opponentChoice),
        )
        return ResolveOutcome.DoubleRefund(stake = 0L, initiatorNewBalance = 0L, opponentNewBalance = 0L)
    }

    private fun freePlayWin(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        guildId: Long,
        winnerChoice: RpsEngine.Choice,
        loserChoice: RpsEngine.Choice,
    ): ResolveOutcome.Win {
        val xpGranted = xpAwardService.award(
            discordId = winnerDiscordId,
            guildId = guildId,
            amount = WIN_XP,
            reason = "rps:win",
        )
        return ResolveOutcome.Win(
            winnerDiscordId = winnerDiscordId,
            loserDiscordId = loserDiscordId,
            winnerChoice = winnerChoice,
            loserChoice = loserChoice,
            stake = 0L,
            pot = 0L,
            winnerNewBalance = 0L,
            loserNewBalance = 0L,
            lossTribute = 0L,
            xpGranted = xpGranted,
        )
    }

    /**
     * Cosmetic placeholder used in win embeds when the loser never
     * picked — the renderer wants a Choice value to show in the
     * "vs" line. Picks the choice that the winner's actual choice
     * beats so the embed visually reads "rock crushes scissors" even
     * though scissors was never chosen. Pure UX flavor; doesn't affect
     * the wager arithmetic.
     */
    private fun pickPlaceholder(winning: RpsEngine.Choice): RpsEngine.Choice = when (winning) {
        RpsEngine.Choice.ROCK -> RpsEngine.Choice.SCISSORS
        RpsEngine.Choice.PAPER -> RpsEngine.Choice.ROCK
        RpsEngine.Choice.SCISSORS -> RpsEngine.Choice.PAPER
    }

    private fun readStakeBounds(guildId: Long): Pair<Long, Long> {
        val min = configService.cfgLong(
            ConfigDto.Configurations.RPS_MIN_STAKE, guildId, default = MIN_STAKE, min = 0L
        )
        val max = configService.cfgLongMax(
            ConfigDto.Configurations.RPS_MAX_STAKE, guildId, default = MAX_STAKE, min = min
        )
        return min to max
    }

    companion object {
        /** Allow stake-free play: 0 is a valid minimum. */
        const val MIN_STAKE: Long = 0L
        const val MAX_STAKE: Long = 500L

        /** XP awarded to the winner regardless of stake. Daily-cap-respecting via XpAwardService. */
        const val WIN_XP: Long = 10L
    }
}
