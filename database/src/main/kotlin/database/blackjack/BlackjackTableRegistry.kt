package database.blackjack

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of blackjack tables (both SOLO single-hand tables and
 * MULTI shared-dealer tables). Trimmed-down sibling of
 * [database.poker.PokerTableRegistry] — no shot clock for v1, but the
 * same idle-sweeper + per-table monitor + onIdleEvict callback shape.
 *
 * Tables vanish on bot restart. Each player's chips have already been
 * debited from `socialCredit` at deal/ante time, so a restart loses
 * those stakes. The idle sweeper would have refunded them anyway, so
 * this is acceptable for casual cash play.
 */
@Component
class BlackjackTableRegistry(
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
    sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
) {
    private val tables = ConcurrentHashMap<Long, BlackjackTable>()
    private val seq = AtomicLong()
    private var onIdleEvict: ((BlackjackTable) -> Unit)? = null

    /**
     * Per-table pending shot-clock task. Keyed by tableId; value is the
     * scheduled future that will fire [onShotClockExpired] if the actor
     * doesn't act in time. Mirrors [database.poker.PokerTableRegistry]'s
     * shot-clock book.
     */
    private val shotClocks = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private var onShotClockExpired: ((BlackjackTable) -> Unit)? = null

    init {
        scheduler.scheduleAtFixedRate(
            { runCatching { sweepIdle(Instant.now()) } },
            sweepInterval.toMillis(),
            sweepInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    fun setOnIdleEvict(callback: (BlackjackTable) -> Unit) {
        this.onIdleEvict = callback
    }

    /**
     * Register a callback invoked when an actor's shot clock fires
     * before they've taken an action. The service wires this to its
     * auto-stand path so the registry stays Spring-free.
     */
    fun setOnShotClockExpired(callback: (BlackjackTable) -> Unit) {
        this.onShotClockExpired = callback
    }

    fun create(
        guildId: Long,
        mode: BlackjackTable.Mode,
        hostDiscordId: Long?,
        ante: Long,
        maxSeats: Int,
        shotClockSeconds: Int = 0,
        rules: BlackjackTable.TableRules = BlackjackTable.TableRules(),
        now: Instant = Instant.now()
    ): BlackjackTable {
        val id = seq.incrementAndGet()
        val table = BlackjackTable(
            id = id,
            guildId = guildId,
            mode = mode,
            hostDiscordId = hostDiscordId,
            ante = ante,
            maxSeats = maxSeats,
            shotClockSeconds = shotClockSeconds,
            rules = rules,
            lastActivityAt = now
        )
        tables[id] = table
        return table
    }

    /**
     * Cancel the pending shot-clock task for [tableId] (if any) and arm
     * a fresh one for the table's current actor. No-op when the table's
     * [BlackjackTable.shotClockSeconds] is `0` or the table is not in
     * PLAYER_TURNS. Called by the service every time the actor changes,
     * keyed off `(tableId, handNumber, actorIndex)` so a stale fire is
     * harmless.
     */
    fun rearmShotClock(tableId: Long, now: Instant = Instant.now()) {
        val table = tables[tableId] ?: return
        cancelShotClock(tableId)
        val seconds = table.shotClockSeconds
        if (seconds <= 0 || table.phase != BlackjackTable.Phase.PLAYER_TURNS) {
            table.currentActorDeadline = null
            return
        }
        val deadline = now.plusSeconds(seconds.toLong())
        table.currentActorDeadline = deadline
        val expectedHand = table.handNumber
        val expectedActor = table.actorIndex
        val future = scheduler.schedule(
            {
                runCatching {
                    val live = tables[tableId] ?: return@runCatching
                    synchronized(live) {
                        if (live.handNumber != expectedHand ||
                            live.actorIndex != expectedActor ||
                            live.phase != BlackjackTable.Phase.PLAYER_TURNS
                        ) return@synchronized
                        onShotClockExpired?.invoke(live)
                    }
                }
            },
            seconds.toLong(),
            TimeUnit.SECONDS
        )
        shotClocks[tableId] = future
    }

    /** Cancel any pending shot-clock task for [tableId]. Idempotent. */
    fun cancelShotClock(tableId: Long) {
        val task = shotClocks.remove(tableId) ?: return
        task.cancel(false)
    }

    fun get(tableId: Long): BlackjackTable? = tables[tableId]

    fun listForGuild(guildId: Long): List<BlackjackTable> =
        tables.values.filter { it.guildId == guildId }

    fun remove(tableId: Long): BlackjackTable? = tables.remove(tableId)

    /**
     * Run [action] under the table monitor — every state-mutating
     * operation must go through here so concurrent button clicks
     * serialise around a consistent view of the hand.
     */
    fun <T> lockTable(tableId: Long, action: (BlackjackTable) -> T): T? {
        val table = tables[tableId] ?: return null
        synchronized(table) {
            if (tables[tableId] == null) return null
            return action(table)
        }
    }

    /**
     * Force-evict every table whose `lastActivityAt` is older than
     * [idleTtl]. Public + parameterised so tests can drive it without
     * waiting for the scheduler.
     */
    fun sweepIdle(now: Instant) {
        val cutoff = now.minus(idleTtl)
        for ((id, table) in tables) {
            synchronized(table) {
                if (table.lastActivityAt.isBefore(cutoff)) {
                    val evicted = tables.remove(id)
                    cancelShotClock(id)
                    if (evicted != null) runCatching { onIdleEvict?.invoke(evicted) }
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        val DEFAULT_IDLE_TTL: Duration = Duration.ofMinutes(10)
        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
