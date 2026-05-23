package database.service

import common.events.AntiAutoclickEvent
import database.dto.ConfigDto
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import kotlin.random.Random

/**
 * Casino-side anti-autoclicker bias applicator. Pure-logic minigame
 * classes ([common.economy.Coinflip], [common.economy.Dice],
 * [common.economy.SlotMachine], …) stay fair: they return a fair
 * outcome based on RNG, oblivious to the bot gate. This service then
 * substitutes that outcome with a caller-supplied "loss" outcome with
 * probability equal to the player's current bot-suspicion-driven
 * house edge.
 *
 * Mathematically the result is identical to pre-rolling a forced loss
 * inside the pure-logic class (`RTP_effective = (1 − houseEdge) ×
 * RTP_fair`), but the separation keeps each game's pure logic untouched
 * and concentrates the anti-abuse policy in one Spring service that
 * every minigame service injects.
 *
 * Streak source is [CasinoBotSuspicionService]; the per-guild cap comes
 * from a per-game `*_BOT_EDGE_MAX_PCT` config the caller passes in.
 * 0 % cap disables the gate entirely (back to fair behaviour).
 */
@Service
class CasinoEdgeService(
    private val botSuspicionService: CasinoBotSuspicionService,
    private val configService: ConfigService,
    private val eventPublisher: ApplicationEventPublisher,
    private val random: Random = Random.Default,
) {

    /**
     * Run [recordAndScore][CasinoBotSuspicionService.recordAndScore] for
     * the bet's click signature, compute the effective house edge, then
     * with probability `houseEdge` substitute the [fairOutcome] with the
     * result of [asLoss]. Returns the (possibly substituted) outcome.
     *
     * The fair outcome is always computed by the caller before this
     * method runs — this service never re-runs the game. That means
     * forced losses don't change the user's *visible* game RNG draw
     * (e.g. coinflip's animated coin still settles on a deterministic
     * face) — the response shape just gets rewritten as a loss.
     *
     * [edgeMaxConfig] is the per-game `*_BOT_EDGE_MAX_PCT` config key
     * (e.g. `COINFLIP_BOT_EDGE_MAX_PCT`); admin-set whole-number
     * percentage 0–50, default [DEFAULT_EDGE_MAX_PCT], coerced to
     * [HARD_EDGE_MAX_PCT].
     */
    fun <T> applyBotEdge(
        discordId: Long,
        guildId: Long,
        gameKey: String,
        clickX: Int?,
        clickY: Int?,
        mouseMoved: Boolean?,
        edgeMaxConfig: ConfigDto.Configurations,
        fairOutcome: T,
        asLoss: () -> T,
    ): T {
        val streak = botSuspicionService.recordAndScore(
            discordId, guildId, gameKey, clickX, clickY, mouseMoved
        )
        val maxEdgePct = configService.cfgLong(
            edgeMaxConfig, guildId, default = DEFAULT_EDGE_MAX_PCT, min = 0L
        ).coerceAtMost(HARD_EDGE_MAX_PCT)
        val houseEdge = (streak * EDGE_PCT_PER_STREAK / 100.0)
            .coerceIn(0.0, maxEdgePct / 100.0)
        if (houseEdge > 0.0 && random.nextDouble() < houseEdge) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.BiasFired(
                    guildId = guildId,
                    discordId = discordId,
                    gameKey = gameKey,
                    streak = streak,
                    edgePct = houseEdge * 100.0,
                )
            )
            return asLoss()
        }
        return fairOutcome
    }

    companion object {
        /** Per-bet house-edge slope as the suspicion streak grows. 2.5 % per
         *  consecutive bot-like click means streak 12 saturates the default
         *  30 % cap. */
        const val EDGE_PCT_PER_STREAK: Double = 2.5

        /** Default per-game ceiling on the bot-suspicion house edge, in whole
         *  percent. Active when the per-game `*_BOT_EDGE_MAX_PCT` config is
         *  unset. */
        const val DEFAULT_EDGE_MAX_PCT: Long = 30L

        /** Hard upper bound for the configurable cap. 50 % of bets becoming
         *  forced losses is already aggressive — beyond that the game is
         *  unplayable for false positives. */
        const val HARD_EDGE_MAX_PCT: Long = 50L
    }
}
