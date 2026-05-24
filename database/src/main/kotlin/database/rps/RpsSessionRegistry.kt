package database.rps

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import common.rps.RpsEngine
import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of live `/rps` matches. Two stages:
 *   - PENDING — `/rps` posted; opponent hasn't yet hit Accept. Stakes
 *     are NOT yet debited from anyone.
 *   - LIVE — opponent accepted; both stakes locked. Each side privately
 *     submits a [RpsEngine.Choice] via [recordPick]; once both have, the
 *     caller drains the session (via [consumeForResolution]) and
 *     resolves the wager.
 *
 * **Storage**: backed by a Caffeine [Cache] (via `asMap()` for the
 * familiar `ConcurrentMap` ops) so we get a hard upper bound on
 * concurrent sessions ([MAX_SESSIONS]) and a TTL backstop
 * ([MAX_LIFETIME]) on entries that the scheduler somehow misses. This
 * matches the convention used by `MessageChatListener` and the SSE
 * registry elsewhere in the codebase — belt-and-suspenders memory
 * protection beyond the scheduler-driven phase callbacks.
 *
 * The phase timeouts (pending → expired, live-no-pick → expired) are
 * still driven by [scheduler] because the callbacks close over JDA
 * message-edit state. Caffeine's eviction-by-TTL kicks in only if a
 * scheduled task somehow doesn't run (queue overflow, JVM pause,
 * throw); the scheduler's `sessions.remove(id)` returns null in that
 * case and skips the message edit — memory is already reclaimed.
 *
 * Lost-on-restart is acceptable: mid-flight matches are short-lived
 * and the worst case (a session evaporates mid-pick) just refunds
 * anything already debited — better than carrying half-state across
 * deploys.
 */
@Component
class RpsSessionRegistry(
    val pendingTtl: Duration = DEFAULT_PENDING_TTL,
    val pickTtl: Duration = DEFAULT_PICK_TTL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
    maximumSessions: Long = MAX_SESSIONS,
    maxLifetime: Duration = MAX_LIFETIME,
) {

    data class Session(
        val id: Long,
        val guildId: Long,
        val initiatorDiscordId: Long,
        val opponentDiscordId: Long,
        val stake: Long,
        val createdAt: Instant,
        /** Picks indexed by discord id; both populated → ready to resolve. */
        val picks: MutableMap<Long, RpsEngine.Choice> = mutableMapOf(),
        var state: State = State.PENDING,
    ) {
        enum class State { PENDING, LIVE }
        val bothPicked: Boolean get() = picks.size == 2
    }

    /**
     * Caffeine cache + `asMap()` view. Every read/write below uses
     * [sessions] (the ConcurrentMap view) for atomic primitives;
     * [cache] is kept only so the `expireAfterWrite` / `maximumSize`
     * configuration stays in scope and the GC root chain is explicit.
     */
    private val cache: Cache<Long, Session> = Caffeine.newBuilder()
        .expireAfterWrite(maxLifetime)
        .maximumSize(maximumSessions)
        .build()
    private val sessions: ConcurrentMap<Long, Session> = cache.asMap()
    private val seq = AtomicLong()

    /**
     * Register a fresh PENDING offer. Schedules a timeout that fires
     * only if the offer is still pending — once accepted (LIVE) the
     * pending timer is a no-op and the pick timer takes over.
     */
    fun register(
        guildId: Long,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        createdAt: Instant = Instant.now(),
        onPendingTimeout: (Session) -> Unit = {},
    ): Session {
        val id = seq.incrementAndGet()
        val session = Session(
            id = id,
            guildId = guildId,
            initiatorDiscordId = initiatorDiscordId,
            opponentDiscordId = opponentDiscordId,
            stake = stake,
            createdAt = createdAt,
        )
        sessions[id] = session
        scheduler.schedule({
            // Only fire if still pending — once accepted, the session
            // transitions to LIVE and the pick timer takes over below.
            val current = sessions[id]
            if (current != null && current.state == Session.State.PENDING) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPendingTimeout(expired) }
            }
        }, pendingTtl.toMillis(), TimeUnit.MILLISECONDS)
        return session
    }

    /**
     * Transition PENDING → LIVE on the opponent's Accept. Returns the
     * session or null if it already expired or was cancelled. Schedules
     * a pick-phase timeout. The atomic compare-and-set on `state`
     * means concurrent Accept/Decline/timeout collapses to one winner.
     */
    fun accept(id: Long, onPickTimeout: (Session) -> Unit = {}): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            session.state = Session.State.LIVE
        }
        scheduler.schedule({
            // Only fire if no resolution has consumed the session yet.
            val current = sessions[id]
            if (current != null && current.state == Session.State.LIVE && !current.bothPicked) {
                val expired = sessions.remove(id)
                if (expired != null) runCatching { onPickTimeout(expired) }
            }
        }, pickTtl.toMillis(), TimeUnit.MILLISECONDS)
        return session
    }

    /** Atomic remove for decline (opponent rejects PENDING offer). */
    fun decline(id: Long): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.PENDING) return null
            return sessions.remove(id)
        }
    }

    /**
     * Records a player's pick. Returns the updated session (always
     * non-null on success) so callers can read [Session.bothPicked]
     * and decide whether to call [consumeForResolution]. Returns null
     * if the session no longer exists or the player isn't in it.
     */
    fun recordPick(id: Long, discordId: Long, choice: RpsEngine.Choice): Session? {
        val session = sessions[id] ?: return null
        synchronized(session) {
            if (session.state != Session.State.LIVE) return null
            if (discordId != session.initiatorDiscordId && discordId != session.opponentDiscordId) return null
            session.picks[discordId] = choice
            return session
        }
    }

    /**
     * Atomic remove for resolution — call only once [Session.bothPicked]
     * is true. Returns the session or null if another thread already
     * resolved it.
     */
    fun consumeForResolution(id: Long): Session? = sessions.remove(id)

    /**
     * Atomic remove for forfeit (a player gives up mid-pick or the
     * Accept-side initiator wants to back out). Returns the session
     * or null if already gone.
     */
    fun forfeit(id: Long): Session? = sessions.remove(id)

    fun get(id: Long): Session? = sessions[id]

    /**
     * List PENDING offers where [discordId] is the opponent — the web
     * inbox feed. Scans the Caffeine map (O(n), capped at
     * [MAX_SESSIONS]); fine because n is bounded and the alternative
     * (a parallel index keyed on opponent id) would have to be kept in
     * sync through every state transition. Same approach as
     * [PendingDuelRegistry.pendingForOpponent].
     */
    fun pendingForOpponent(discordId: Long, guildId: Long): List<Session> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.opponentDiscordId == discordId
        }

    /** Mirror of [pendingForOpponent] for outgoing offers (web outbox). */
    fun pendingForInitiator(discordId: Long, guildId: Long): List<Session> =
        sessions.values.filter {
            it.state == Session.State.PENDING &&
                it.guildId == guildId &&
                it.initiatorDiscordId == discordId
        }

    /**
     * List LIVE sessions where [discordId] is one of the two participants
     * — the web active-game feed. Used by the polling endpoint that
     * keeps the board in sync between Discord and web surfaces.
     */
    fun liveFor(discordId: Long, guildId: Long): List<Session> =
        sessions.values.filter {
            it.state == Session.State.LIVE &&
                it.guildId == guildId &&
                (it.initiatorDiscordId == discordId || it.opponentDiscordId == discordId)
        }

    companion object {
        val DEFAULT_PENDING_TTL: Duration = Duration.ofMinutes(3)
        val DEFAULT_PICK_TTL: Duration = Duration.ofMinutes(2)

        /**
         * Hard upper bound on concurrent in-flight sessions. Matches the
         * convention used by `MessageChatListener.lastAwardAt` —
         * generous enough that a real PvP burst (a server runs a
         * tournament) doesn't trip it, low enough that a malformed
         * `/rps` flood can't OOM the heap. Caffeine evicts least-
         * recently-used entries above the cap.
         */
        const val MAX_SESSIONS: Long = 10_000L

        /**
         * Backstop lifetime for any single session: pending TTL (3m)
         * + pick TTL (2m) + 1m slop = 6 minutes. Caffeine evicts
         * entries past this even if the scheduler-driven phase
         * timeouts somehow didn't run; protects against memory leaks
         * if a scheduled task throws or the executor queue saturates.
         */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(6)
    }
}
