package database.poker

import database.configuration.RegistryScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory book of singleplayer Casino Hold'em tables. Trimmed sibling
 * of [database.blackjack.BlackjackTableRegistry]: solo-only, one seat
 * per table, no shot clock — but the same idle-sweeper + per-table
 * monitor + onIdleEvict callback shape so the rest of the casino code
 * looks uniform.
 *
 * Tables vanish on bot restart. Each player's stake has already been
 * debited from `socialCredit` at deal time; the idle sweeper would have
 * refunded them anyway, so this is acceptable for casual play.
 */
@Component
class CasinoHoldemTableRegistry(
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
    sweepInterval: Duration = DEFAULT_SWEEP_INTERVAL,
    private val scheduler: ScheduledExecutorService = RegistryScheduler.instance,
) {
    private val tables = ConcurrentHashMap<Long, CasinoHoldemTable>()
    private val seq = AtomicLong()
    private var onIdleEvict: ((CasinoHoldemTable) -> Unit)? = null

    init {
        scheduler.scheduleAtFixedRate(
            { runCatching { sweepIdle(Instant.now()) } },
            sweepInterval.toMillis(),
            sweepInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    fun setOnIdleEvict(callback: (CasinoHoldemTable) -> Unit) {
        this.onIdleEvict = callback
    }

    fun create(
        guildId: Long,
        playerDiscordId: Long,
        stake: Long,
        now: Instant = Instant.now(),
    ): CasinoHoldemTable {
        val id = seq.incrementAndGet()
        val table = CasinoHoldemTable(
            id = id,
            guildId = guildId,
            playerDiscordId = playerDiscordId,
            stake = stake,
            lastActivityAt = now,
        )
        tables[id] = table
        return table
    }

    fun get(tableId: Long): CasinoHoldemTable? = tables[tableId]

    fun listForGuild(guildId: Long): List<CasinoHoldemTable> =
        tables.values.filter { it.guildId == guildId }

    fun remove(tableId: Long): CasinoHoldemTable? = tables.remove(tableId)

    /**
     * Run [action] under the table monitor — every state-mutating
     * operation must go through here so concurrent button clicks
     * serialise around a consistent view of the hand.
     */
    fun <T> lockTable(tableId: Long, action: (CasinoHoldemTable) -> T): T? {
        val table = tables[tableId] ?: return null
        synchronized(table) {
            if (tables[tableId] == null) return null
            return action(table)
        }
    }

    /**
     * Force-evict every table whose `lastActivityAt` is older than
     * [idleTtl]. Public + parameterised so tests can drive it without
     * waiting for the scheduler. Each evicted table is handed to
     * [onIdleEvict] under the table monitor so the service can refund
     * an unresolved stake.
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

    companion object {
        val DEFAULT_IDLE_TTL: Duration = Duration.ofMinutes(10)
        val DEFAULT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
