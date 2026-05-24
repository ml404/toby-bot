package database.service.casino

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import common.events.moderation.AntiAutoclickEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Per-(user, guild, gameKey) tracker of bet-click signatures used to
 * detect autoclicker spam on the web casino minigame pages.
 *
 * Heuristic: an autoclicker fires `click` events at a fixed pixel without
 * synthesising the surrounding `mousemove` events. Genuine players move
 * the cursor between bets — even by a few pixels — and the browser emits
 * mousemove notifications during that motion.
 *
 * **Rolling window of the last 100 click signals.** A monotonic streak
 * couldn't tell a determined sit-still slots player apart from a real
 * bot — both produce identical signals during continuous play. The
 * window samples the last [WINDOW_SIZE] bets and only flags when at
 * least [MIN_SAMPLE] of them match the bot pattern *and* the match
 * ratio is at least [RATIO_OPEN]. Even one mouse-twitch or pixel
 * drift per ~20 bets keeps the ratio under threshold; a real bot
 * stays at 1.0.
 *
 * Hysteresis: open at ratio ≥ [RATIO_OPEN], close at ratio < [RATIO_CLOSE].
 * The gap prevents flapping at the boundary as one stray non-match
 * eviction shifts the ratio.
 *
 * Idle reset: if the gap between bets exceeds [IDLE_RESET_MS] (5 min),
 * the window clears. A user who comes back from a break starts on a
 * fresh slate; old bot-shaped signals don't haunt them.
 *
 * State is held in an in-memory Caffeine cache (bounded
 * [STATES_MAX_SIZE] / idle TTL [STATES_TTL_MIN] minutes). Restart-resets
 * are acceptable: a real bot rebuilds its window in [WINDOW_SIZE] bets,
 * and persisting suspicion data through a deploy is more complexity than
 * the heuristic warrants. The cap + TTL backstop a pre-existing growth
 * path — `evictGuild` only fires on bot-leave, so without an idle TTL a
 * user who plays once leaves a permanent entry.
 */
@Service
class CasinoBotSuspicionService(
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemDefaultZone(),
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
        // Match-history bitset for the last WINDOW_SIZE bets. true = the
        // bet's click signals matched the bot pattern (same pixel ±2 px,
        // mouseMoved == false). false = at least one signal differed.
        val window: ArrayDeque<Boolean>,
        var lastClickTimeMs: Long,
        var currentlyOpen: Boolean,
    )

    private val states: Cache<Triple<Long, Long, String>, State> = Caffeine.newBuilder()
        .maximumSize(STATES_MAX_SIZE)
        .expireAfterAccess(STATES_TTL_MIN, TimeUnit.MINUTES)
        .build()

    /**
     * Record a bet's click signature and return the current bot-suspicion
     * "streak" used by [CasinoEdgeService] for the per-game forced-loss
     * gate. Returns the count of matched bets in the window when the
     * session is currently open, else 0 — so a session below the
     * detection threshold contributes no house-edge bias even if some
     * recent bets happen to match.
     *
     * Caller is expected to invoke this inside the same `@Transactional`
     * boundary as the wager itself so the window update and the bias
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

        // Missing any of the three signals → treat as a non-suspicious
        // bet (Discord path, keyboard submit, browser without JS). Drop
        // the whole window so a previously suspicious user gets a clean
        // slate when they switch input modality, and close the session
        // if it was open.
        if (clickX == null || clickY == null || mouseMoved == null) {
            val priorEntry = states.getIfPresent(key)?.also { states.invalidate(key) }
            if (priorEntry?.currentlyOpen == true) {
                eventPublisher.publishEvent(
                    AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)
                )
            }
            return 0
        }

        val now = clock.millis()
        val prior = states.getIfPresent(key)

        // Idle reset — a long gap between bets clears the window. Real
        // autoclickers fire continuously; humans take breaks.
        if (prior != null && (now - prior.lastClickTimeMs) > IDLE_RESET_MS) {
            prior.window.clear()
            if (prior.currentlyOpen) {
                prior.currentlyOpen = false
                eventPublisher.publishEvent(
                    AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)
                )
            }
        }

        // Match check: same pixel within epsilon AND no motion since the
        // previous bet. The first bet of a window can't match (no prior
        // pixel to compare) and contributes a `false` so the ratio
        // can't be inflated by a session of length 1.
        val matched = prior != null &&
            !mouseMoved &&
            kotlin.math.abs(prior.lastX - clickX) <= EPSILON_PX &&
            kotlin.math.abs(prior.lastY - clickY) <= EPSILON_PX

        val window = prior?.window ?: ArrayDeque<Boolean>(WINDOW_SIZE)
        window.addLast(matched)
        while (window.size > WINDOW_SIZE) window.removeFirst()

        val size = window.size
        val matches = window.count { it }
        val ratio = if (size > 0) matches.toDouble() / size else 0.0
        val wasOpen = prior?.currentlyOpen ?: false

        val nowOpen = when {
            wasOpen && ratio < RATIO_CLOSE -> false
            !wasOpen && size >= MIN_SAMPLE && ratio >= RATIO_OPEN -> true
            else -> wasOpen
        }

        states.put(
            key,
            State(
                lastX = clickX,
                lastY = clickY,
                window = window,
                lastClickTimeMs = now,
                currentlyOpen = nowOpen,
            ),
        )

        if (!wasOpen && nowOpen) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionOpened(guildId, discordId, gameKey, streak = matches)
            )
        } else if (wasOpen && !nowOpen) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionClosed(guildId, discordId, gameKey)
            )
        }

        // Return the matches count only while the session is open — the
        // edge gate only ramps when the rolling window has confirmed
        // the bot pattern at threshold. Below-threshold sessions report
        // 0 so [CasinoEdgeService]'s `streak * 2.5pp` formula yields
        // zero bias for natural play.
        return if (nowOpen) matches else 0
    }

    /**
     * Drop every tracked window for [guildId]. Called when the bot
     * leaves the guild — the window state is meaningless once the
     * casino pages can no longer post bets for that guild.
     */
    fun evictGuild(guildId: Long) {
        val keysForGuild = states.asMap().keys.filter { it.second == guildId }
        if (keysForGuild.isNotEmpty()) states.invalidateAll(keysForGuild)
    }

    companion object {
        /** Hard ceiling on tracked (user, guild, game) triples. Sized for
         *  thousands of concurrent casino players across all guilds — at
         *  ~440 B per State, 10k entries is ~4 MB worst-case resident. */
        const val STATES_MAX_SIZE: Long = 10_000L

        /** Idle TTL for a triple's State. A user who walks away from the
         *  casino has their suspicion window dropped after this window —
         *  no permanent map entries from one-and-done players. Long enough
         *  that a real grinder's session keeps its window across snack
         *  breaks. */
        const val STATES_TTL_MIN: Long = 30L

        const val EPSILON_PX: Int = 2

        /** Number of recent bets the window keeps. Sized large enough
         *  to absorb [MIN_SAMPLE] bets so the ratio measures over the
         *  full session-of-interest, not a sliding tail of it. */
        const val WINDOW_SIZE: Int = 300

        /** Minimum window fill before the gate can open. 300 bets at
         *  slots' 800 ms minimum spin = ~4 min of *uninterrupted*
         *  identical clicking. A natural multi-minute session that
         *  includes any conscious pauses never reaches it (the
         *  [IDLE_RESET_MS] cutoff clears the window during the pause). */
        const val MIN_SAMPLE: Int = 300

        /** Match ratio (matches / size) at which the gate opens. 95 %
         *  means even one mouse-twitch or slight pixel drift per 20 bets
         *  keeps a human under threshold. */
        const val RATIO_OPEN: Double = 0.95

        /** Match ratio at which an open gate closes. Hysteresis prevents
         *  flapping when one stray match-eviction nudges the ratio
         *  across the open threshold. */
        const val RATIO_CLOSE: Double = 0.60

        /** Idle gap between bets that clears the window entirely. Set
         *  to 15 s — natural slots cycles are 1–3 s (animation + react
         *  + click), so a 15 s gap reliably indicates a conscious
         *  pause. Players who glance at chat, sip a drink, or stop to
         *  read a result hit this cutoff and start a fresh window;
         *  only continuous bot-grade play accumulates toward
         *  [MIN_SAMPLE]. */
        const val IDLE_RESET_MS: Long = 15L * 1000L
    }
}
