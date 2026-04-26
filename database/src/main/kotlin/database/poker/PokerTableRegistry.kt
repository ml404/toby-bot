package database.poker

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of poker tables (lobby + in-progress hands). Shared
 * across the whole Spring context so the Discord button handler and
 * the web controller see the same table state — there is exactly one
 * authoritative bean.
 *
 * Per-table mutations must be performed inside a [lockTable] block so
 * concurrent button clicks / web POSTs serialise around hand state.
 *
 * Tables are flushed by an idle sweeper after [idleTtl] of no activity:
 * any seated chips are returned to the player's wallet via the supplied
 * [onIdleEvict] callback (wired by [database.service.PokerService] at
 * startup so the registry stays Spring-only).
 *
 * Mid-flight tables vanish on bot restart. Each player's chips have
 * been debited from `socialCredit` at buy-in time, so a restart loses
 * those stakes — acceptable for a casual cash table since tables are
 * meant to be short-lived and the idle sweep would have cashed them
 * out anyway.
 */
@Component
class PokerTableRegistry(
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
    sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
) {
    private val tables = ConcurrentHashMap<Long, PokerTable>()
    private val seq = AtomicLong()
    private var onIdleEvict: ((PokerTable) -> Unit)? = null

    init {
        scheduler.scheduleAtFixedRate(
            { runCatching { sweepIdle(Instant.now()) } },
            sweepInterval.toMillis(),
            sweepInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Register a per-table cleanup callback invoked when an idle table
     * is evicted. Wired once at startup by [database.service.PokerService]
     * so the registry can stay free of Spring-managed dependencies.
     */
    fun setOnIdleEvict(callback: (PokerTable) -> Unit) {
        this.onIdleEvict = callback
    }

    fun create(
        guildId: Long,
        hostDiscordId: Long,
        minBuyIn: Long,
        maxBuyIn: Long,
        smallBlind: Long,
        bigBlind: Long,
        smallBet: Long,
        bigBet: Long,
        maxRaisesPerStreet: Int,
        maxSeats: Int,
        now: Instant = Instant.now()
    ): PokerTable {
        val id = seq.incrementAndGet()
        val table = PokerTable(
            id = id,
            guildId = guildId,
            hostDiscordId = hostDiscordId,
            minBuyIn = minBuyIn,
            maxBuyIn = maxBuyIn,
            smallBlind = smallBlind,
            bigBlind = bigBlind,
            smallBet = smallBet,
            bigBet = bigBet,
            maxRaisesPerStreet = maxRaisesPerStreet,
            maxSeats = maxSeats,
            lastActivityAt = now
        )
        tables[id] = table
        return table
    }

    fun get(tableId: Long): PokerTable? = tables[tableId]

    fun listForGuild(guildId: Long): List<PokerTable> =
        tables.values.filter { it.guildId == guildId }

    fun remove(tableId: Long): PokerTable? = tables.remove(tableId)

    /**
     * Run [action] under the table monitor — every state-mutating
     * operation (seat, leave, action, start) must go through here so
     * concurrent Discord button clicks and web POSTs serialise around a
     * consistent view of the hand.
     */
    fun <T> lockTable(tableId: Long, action: (PokerTable) -> T): T? {
        val table = tables[tableId] ?: return null
        synchronized(table) {
            // Re-check under the monitor in case removal raced with us.
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
