package database.service

import common.events.AntiAutoclickEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-(user, guild, gameKey) tracker of bet-click signatures used to
 * detect autoclicker spam on the web casino minigame pages.
 *
 * Heuristic: an autoclicker fires `click` events at a fixed pixel without
 * synthesising the surrounding `mousemove` events. Genuine players move
 * the cursor between bets — even by a few pixels — and the browser emits
 * mousemove notifications during that motion.
 *
 * On every bet, [recordAndScore] compares the supplied `clickX`/`clickY`
 * against the user's previous bet **for the same game** and consults the
 * supplied `mouseMoved` flag. When **both** signal "bot-like" (same spot
 * ± [EPSILON_PX], no intervening motion), the user's streak for that
 * game increments. Any other outcome — coordinates outside the epsilon,
 * `mouseMoved == true`, or any field `null` (e.g. Discord call paths or
 * the rare keyboard submit) — resets the streak to 0. [CasinoEdgeService]
 * reads this streak and converts it to an effective house edge.
 *
 * State is partitioned by `gameKey` so a player's slots streak doesn't
 * bleed into their dice or coinflip streak. Each game uses a stable
 * string identifier (`"coinflip"`, `"dice"`, `"slots"`).
 *
 * State is held in an in-memory [ConcurrentHashMap]. Restart-resets are
 * acceptable: a real bot rebuilds the streak in a handful of bets, and
 * persisting suspicion data through a deploy is more complexity than
 * the heuristic warrants.
 */
@Service
class CasinoBotSuspicionService(
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * Maximum pixel distance between consecutive bet clicks that still
     * counts as "same spot." Two pixels swallows sub-pixel rendering
     * jitter and trackpad finger-tip wobble while staying tight enough
     * that an autoclicker pinned to one coordinate trips reliably.
     */
    val epsilonPx: Int get() = EPSILON_PX

    private data class State(
        var lastX: Int,
        var lastY: Int,
        var streak: Int,
    )

    private val states: ConcurrentHashMap<Triple<Long, Long, String>, State> = ConcurrentHashMap()

    /**
     * Record a bet's click signature and return the resulting consecutive
     * "bot-like" streak for the supplied [gameKey]. The streak grows when
     * the new click is within [EPSILON_PX] of the previous click for the
     * same game and `mouseMoved` is `false`; any other combination —
     * including any `null` signal field — resets it to 0.
     *
     * Caller is expected to invoke this inside the same `@Transactional`
     * boundary as the wager itself so the streak update and the bias
     * decision are observed together.
     */
    fun recordAndScore(
        discordId: Long,
        guildId: Long,
        gameKey: String,
        clickX: Int?,
        clickY: Int?,
        mouseMoved: Boolean?,
    ): Int {
        val key = Triple(discordId, guildId, gameKey)
        // Missing any of the three signals → treat as a non-suspicious bet
        // (Discord path, keyboard submit, browser without JS). Reset the
        // streak so a previously suspicious user gets a clean slate when
        // they switch input modality.
        if (clickX == null || clickY == null || mouseMoved == null) {
            val priorEntry = states.remove(key)
            if ((priorEntry?.streak ?: 0) >= 1) {
                eventPublisher.publishEvent(
                    AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)
                )
            }
            return 0
        }

        val prior = states[key]
        val priorStreak = prior?.streak ?: 0
        val newStreak = if (
            prior != null &&
            !mouseMoved &&
            kotlin.math.abs(prior.lastX - clickX) <= EPSILON_PX &&
            kotlin.math.abs(prior.lastY - clickY) <= EPSILON_PX
        ) {
            prior.streak + 1
        } else {
            0
        }
        states[key] = State(lastX = clickX, lastY = clickY, streak = newStreak)
        if (priorStreak == 0 && newStreak >= 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionOpened(guildId, discordId, gameKey, newStreak)
            )
        } else if (priorStreak >= 1 && newStreak == 0) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)
            )
        }
        return newStreak
    }

    companion object {
        const val EPSILON_PX: Int = 2
    }
}
