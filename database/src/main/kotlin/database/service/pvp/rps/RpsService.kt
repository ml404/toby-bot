package database.service.pvp.rps

import common.events.pvp.rps.RpsResolvedEvent
import common.rps.RpsEngine
import database.dto.guild.ConfigDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import database.service.pvp.PvpWagerService

/**
 * Head-to-head Rock-Paper-Scissors wager between two users. RPS-specific
 * resolve branches live here; the shared wager primitives (start
 * preflight, accept debit, winner pay, draw refund) delegate to
 * [PvpWagerService] so the same arithmetic powers `/rps`, `/tictactoe`
 * (and the planned `/connect4`).
 *
 *   - [startMatch] / [acceptMatch] are thin pass-throughs to the
 *     shared service; the only RPS-specific bit is the per-guild
 *     config-key wiring.
 *   - [resolveMatch] handles the four RPS-flavoured branches: both
 *     picked + clean winner, both picked + identical-move draw,
 *     exactly one picked (forfeit), and neither picked (double
 *     refund).
 *
 * Publishes [RpsResolvedEvent] on every winner-bearing match — free
 * play included — so achievements unlock regardless of whether anyone
 * bet. Draws and double-no-pick never publish.
 *
 * Has **no JDA dependency by design** (PR #551 lesson).
 */
@Service
@Transactional
class RpsService @Autowired constructor(
    private val pvpWagerService: PvpWagerService,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

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

        /** Unreachable defensive case — one of the player rows vanished mid-resolve. */
        data object Unknown : ResolveOutcome
    }

    fun startMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): PvpWagerService.StartOutcome {
        val (min, max) = pvpWagerService.readStakeBounds(
            guildId, ConfigDto.Configurations.RPS_MIN_STAKE, ConfigDto.Configurations.RPS_MAX_STAKE,
            defaultMin = MIN_STAKE, defaultMax = MAX_STAKE,
        )
        return pvpWagerService.preflightStart(
            initiatorDiscordId = initiatorDiscordId,
            opponentDiscordId = opponentDiscordId,
            guildId = guildId,
            stake = stake,
            minStake = min,
            maxStake = max,
        )
    }

    fun acceptMatch(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): PvpWagerService.AcceptOutcome =
        pvpWagerService.debitBoth(initiatorDiscordId, opponentDiscordId, guildId, stake)

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
        // Case A: both picked.
        if (initiatorChoice != null && opponentChoice != null) {
            return when (RpsEngine.resolve(initiatorChoice, opponentChoice)) {
                RpsEngine.Outcome.Draw -> resolveDraw(
                    initiatorDiscordId, opponentDiscordId, guildId, stake, initiatorChoice,
                )
                RpsEngine.Outcome.FirstWins -> resolveWin(
                    winnerDiscordId = initiatorDiscordId, loserDiscordId = opponentDiscordId,
                    guildId = guildId, stake = stake,
                    winnerChoice = initiatorChoice, loserChoice = opponentChoice,
                )
                RpsEngine.Outcome.SecondWins -> resolveWin(
                    winnerDiscordId = opponentDiscordId, loserDiscordId = initiatorDiscordId,
                    guildId = guildId, stake = stake,
                    winnerChoice = opponentChoice, loserChoice = initiatorChoice,
                )
            }
        }
        // Case B: exactly one picked → forfeit win for the picker.
        if (initiatorChoice != null) {
            return resolveWin(
                winnerDiscordId = initiatorDiscordId, loserDiscordId = opponentDiscordId,
                guildId = guildId, stake = stake,
                winnerChoice = initiatorChoice, loserChoice = pickPlaceholder(initiatorChoice),
            )
        }
        if (opponentChoice != null) {
            return resolveWin(
                winnerDiscordId = opponentDiscordId, loserDiscordId = initiatorDiscordId,
                guildId = guildId, stake = stake,
                winnerChoice = opponentChoice, loserChoice = pickPlaceholder(opponentChoice),
            )
        }
        // Case C: neither picked → double refund.
        return resolveDoubleRefund(initiatorDiscordId, opponentDiscordId, guildId, stake)
    }

    private fun resolveWin(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        guildId: Long,
        stake: Long,
        winnerChoice: RpsEngine.Choice,
        loserChoice: RpsEngine.Choice,
    ): ResolveOutcome {
        val pay = pvpWagerService.payWinner(
            winnerDiscordId = winnerDiscordId, loserDiscordId = loserDiscordId,
            stake = stake, guildId = guildId, xpReason = WIN_XP_REASON, xpAmount = WIN_XP,
        ) ?: return ResolveOutcome.Unknown
        val outcome = ResolveOutcome.Win(
            winnerDiscordId = winnerDiscordId,
            loserDiscordId = loserDiscordId,
            winnerChoice = winnerChoice,
            loserChoice = loserChoice,
            stake = stake,
            pot = pay.pot,
            winnerNewBalance = pay.winnerNewBalance,
            loserNewBalance = pay.loserNewBalance,
            lossTribute = pay.lossTribute,
            xpGranted = pay.xpGranted,
        )
        publishResolved(outcome, guildId)
        return outcome
    }

    private fun resolveDraw(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
        choice: RpsEngine.Choice,
    ): ResolveOutcome.Draw {
        val refund = pvpWagerService.refundBoth(initiatorDiscordId, opponentDiscordId, stake, guildId)
        return ResolveOutcome.Draw(
            choice = choice,
            stake = stake,
            initiatorNewBalance = refund.initiatorNewBalance,
            opponentNewBalance = refund.opponentNewBalance,
        )
    }

    private fun resolveDoubleRefund(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        guildId: Long,
        stake: Long,
    ): ResolveOutcome.DoubleRefund {
        val refund = pvpWagerService.refundBoth(initiatorDiscordId, opponentDiscordId, stake, guildId)
        return ResolveOutcome.DoubleRefund(
            stake = stake,
            initiatorNewBalance = refund.initiatorNewBalance,
            opponentNewBalance = refund.opponentNewBalance,
        )
    }

    /**
     * Surfaces the resolution to `AchievementEventHandler` for
     * `first_rps_win` / `rps_wins_*` / `rps_losses_*` progression.
     * Draws and double-no-pick never publish — no winner, no loser.
     * Free-play wins DO publish so achievements unlock regardless of
     * whether anyone bet.
     */
    private fun publishResolved(outcome: ResolveOutcome.Win, guildId: Long) {
        eventPublisher?.publishEvent(
            RpsResolvedEvent(
                winnerDiscordId = outcome.winnerDiscordId,
                loserDiscordId = outcome.loserDiscordId,
                guildId = guildId,
                stake = outcome.stake,
                pot = outcome.pot,
            )
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

    companion object {
        /** Allow stake-free play: 0 is a valid minimum. */
        const val MIN_STAKE: Long = 0L
        const val MAX_STAKE: Long = 500L

        /** XP awarded to the winner regardless of stake. */
        const val WIN_XP: Long = 10L
        private const val WIN_XP_REASON = "rps:win"
    }
}
