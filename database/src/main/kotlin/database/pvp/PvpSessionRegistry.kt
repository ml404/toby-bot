package database.pvp

import database.configuration.RegistryScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Game-agnostic base for in-memory PvP wager session registries
 * (`/rps`, `/tictactoe`, `/connect4`, future games). Owns everything
 * that doesn't depend on whether the game is turn-based with a per-move
 * shot clock or simultaneous-pick:
 *
 *  - Caffeine-backed [ConcurrentMap] with a hard upper bound
 *    ([MAX_SESSIONS]) and TTL backstop ([MAX_LIFETIME])
 *  - PENDING → LIVE state machine + atomic pending-phase timeout
 *  - Indexers: [pendingForOpponent], [pendingForInitiator], [liveFor]
 *  - Atomic remove primitives ([decline], [forfeit], [consumeForResolution])
 *
 * Subclasses decide what happens **after** the PENDING → LIVE transition
 * — turn-based games arm a per-move shot clock; simultaneous-pick games
 * arm a single pick-phase timer. Subclasses implement their own
 * `accept(...)` that calls [transitionPendingToLive] and then schedules
 * whatever LIVE-phase timer they need.
 *
 * Self-typed on [TSession] so the indexer / accessor methods return the
 * concrete subclass without callers having to cast.
 *
 * Lost-on-restart is acceptable: mid-flight matches are short-lived and
 * the worst case (a session evaporates mid-move/pick) just refunds
 * anything already debited.
 */
abstract class PvpSessionRegistry<TSession : PvpSessionRegistry.Session>(
    val pendingTtl: Duration,
    protected val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) {

    /**
     * Game-agnostic session fields. Per-game subclasses extend this
     * with whatever the engine needs to mutate (board, current turn,
     * picks, …).
     */
    abstract class Session(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant,
        var state: State = State.PENDING,
    ) {
        enum class State { PENDING, LIVE }
    }

    /**
     * Subclass-provided factory used by [register]. Receives the
     * session-id the base allocated; the subclass builds its concrete
     * [Session] subclass with that id + any game-specific starting
     * state (fresh board, starting turn, empty picks map, …).
     */
    protected abstract fun newSession(
        id: Long,
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant,
    ): TSession

    /**
     * Bounded + TTL'd `ConcurrentMap` view over a Caffeine cache. See
     * [BoundedSessionCache] for the rationale. Protected so subclasses
     * can override [consumeForResolution] / [forfeit] with extra
     * housekeeping (e.g. cancelling a per-move shot clock).
     */
    protected val sessions: ConcurrentMap<Long, TSession> =
        BoundedSessionCache.build(maxLifetime, maximumSessions)
    private val seq = AtomicLong()

    /**
     * Register a fresh PENDING offer. Schedules a pending-phase timeout
     * that fires only if the offer is still PENDING when it elapses —
     * once accepted (LIVE) the pending timer is a no-op and the
     * subclass's LIVE-phase timer takes over.
     */
    fun register(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant = Instant.now(),
        onPendingTimeout: (TSession) -> Unit = {},
    ): TSession {
        val id = seq.incrementAndGet()
        val session = newSession(id, guildId, initiatorDiscordId, opponentDiscordId, stake, createdAt)
        sessions[id] = session
        scheduler.schedule({
            val current = sessions[id]
            if (current != null && current.state == Session.State.PENDING) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPendingTimeout(expired) }
            }
        }, pendingTtl.toMillis(), TimeUnit.MILLISECONDS)
        return session
    }

    /**
     * Atomic PENDING → LIVE transition. Returns the session (now in
     * LIVE state) or null if it already expired, was declined, or
     * isn't PENDING anymore. Subclasses call this from their public
     * `accept(...)` and follow up with whatever LIVE-phase scheduling
     * they need (shot clock vs single pick-phase timer).
     */
    protected fun transitionPendingToLive(id: Long): TSession? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            session.state = Session.State.LIVE
        }
        return session
    }

    /** Atomic remove for decline (opponent rejects PENDING offer). */
    fun decline(id: Long): TSession? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            return sessions.remove(id)
        }
    }

    /**
     * Atomic remove for resolution — call once a terminal outcome
     * (Win / Draw / forfeit / timeout) is being settled. The base
     * implementation simply removes; subclasses with a LIVE-phase
     * timer override to also cancel it.
     */
    open fun consumeForResolution(id: Long): TSession? = sessions.remove(id)

    /**
     * Atomic remove for forfeit. Delegates to [consumeForResolution]
     * so the override-once-cancel-everywhere contract holds.
     */
    open fun forfeit(id: Long): TSession? = consumeForResolution(id)

    fun get(id: Long): TSession? = sessions[id]

    /**
     * List PENDING offers where [discordId] is the opponent — the web
     * inbox feed. Scans the Caffeine map (O(n), capped at
     * [MAX_SESSIONS]); fine because n is bounded and the alternative
     * (a parallel index keyed on opponent id) would have to be kept in
     * sync through every state transition.
     */
    fun pendingForOpponent(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.opponentDiscordId == discordId
        }

    /** Mirror of [pendingForOpponent] for outgoing offers (web outbox). */
    fun pendingForInitiator(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.initiatorDiscordId == discordId
        }

    /**
     * List LIVE sessions where [discordId] is one of the two
     * participants — the web active-game feed.
     */
    fun liveFor(discordId: Long, guildId: Long): List<TSession> =
        sessions.values.filter {
            it.state == Session.State.LIVE &&
                it.guildId == guildId &&
                (it.initiatorDiscordId == discordId || it.opponentDiscordId == discordId)
        }

    companion object {
        /**
         * Hard upper bound on concurrent in-flight sessions across all
         * subclass registries. Generous enough that a real PvP burst
         * (tournament) doesn't trip it, low enough that a malformed
         * flood can't OOM the heap. Caffeine evicts LRU above the cap.
         */
        const val MAX_SESSIONS: Long = 10_000L

        /**
         * Backstop lifetime per session. Covers pending TTL + the
         * longest reasonable game's worth of LIVE-phase TTLs + slop.
         * Caffeine evicts entries past this even if a scheduled task
         * somehow didn't run.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(12)
    }
}
