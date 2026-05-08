package database.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-(user, guild) tracker of bet-click signatures used to detect
 * autoclicker spam on the web `/coinflip` page.
 *
 * Heuristic: an autoclicker fires `click` events at a fixed pixel without
 * synthesising the surrounding `mousemove` events. Genuine players move
 * the cursor between bets — even by a few pixels — and the browser emits
 * mousemove notifications during that motion.
 *
 * On every bet, [recordAndScore] compares the supplied `clickX`/`clickY`
 * against the user's previous bet and consults the supplied `mouseMoved`
 * flag. When **both** signal "bot-like" (same spot ± [EPSILON_PX], no
 * intervening motion), the user's streak increments. Any other outcome
 * — coordinates outside the epsilon, `mouseMoved == true`, or any field
 * `null` (e.g. Discord call paths or the rare keyboard submit) — resets
 * the streak to 0. [CoinflipService] reads this streak and converts it
 * to an effective house edge.
 *
 * State is held in an in-memory [ConcurrentHashMap]. Restart-resets are
 * acceptable: a real bot rebuilds the streak in a handful of bets, and
 * persisting suspicion data through a deploy is more complexity than
 * the heuristic warrants. The map grows with active casino users and
 * shrinks naturally as the process restarts; for a Discord-bot scale
 * server this is bounded by registered users-per-guild (low thousands
 * at the absolute upper bound).
 */
@Service
class CoinflipBotSuspicionService {

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

    private val states: ConcurrentHashMap<Pair<Long, Long>, State> = ConcurrentHashMap()

    /**
     * Record a bet's click signature and return the resulting consecutive
     * "bot-like" streak. The streak grows when the new click is within
     * [EPSILON_PX] of the previous and `mouseMoved` is `false`; any other
     * combination — including any `null` field — resets it to 0.
     *
     * Caller is expected to read the returned streak inside the same
     * `CoinflipService.flip` transaction so the bias and the recording
     * happen atomically with the wager itself.
     */
    fun recordAndScore(
        discordId: Long,
        guildId: Long,
        clickX: Int?,
        clickY: Int?,
        mouseMoved: Boolean?,
    ): Int {
        val key = discordId to guildId
        // Missing any of the three signals → treat as a non-suspicious bet
        // (Discord path, keyboard submit, browser without JS). Reset the
        // streak so a previously suspicious user gets a clean slate when
        // they switch input modality.
        if (clickX == null || clickY == null || mouseMoved == null) {
            states.remove(key)
            return 0
        }

        val prior = states[key]
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
        return newStreak
    }

    companion object {
        const val EPSILON_PX: Int = 2
    }
}
