package bot.toby.helpers

import net.dv8tion.jda.api.EmbedBuilder
import java.awt.Color
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-guild initiative tracker state. One instance per active guild; DnDHelper
 * manages the Map keyed by guildId. Snapshot/restore methods support Phase 3
 * persistence via CampaignDto.state.
 */
class InitiativeState {

    val initiativeIndex: AtomicInteger = AtomicInteger(0)
    var sortedEntries: LinkedList<RolledEntry> = LinkedList()
        private set

    fun isActive(): Boolean = sortedEntries.isNotEmpty()

    /**
     * Rebuild the sorted list from a `name -> roll` map produced by Discord
     * name-list / voice-channel rolls. Entries produced here carry no `kind`.
     */
    internal fun sortMap(initiativeMap: Map<String, Int>) {
        sortedEntries = LinkedList(
            initiativeMap.entries
                .sortedByDescending { it.value }
                .map { RolledEntry(it.key, it.value, kind = null) }
        )
    }

    /**
     * Web-composer entry point: accept an already-sorted list of rolled entries
     * (players + monsters). Overwrites the current order and resets the pointer.
     */
    internal fun seedFromSorted(entries: List<RolledEntry>) {
        sortedEntries = LinkedList(entries)
        initiativeIndex.set(0)
    }

    fun clear() {
        initiativeIndex.set(0)
        sortedEntries.clear()
    }

    internal fun incrementIndex() {
        initiativeIndex.incrementAndGet()
        if (initiativeIndex.get() >= sortedEntries.size) {
            initiativeIndex.set(0)
        }
    }

    internal fun decrementIndex() {
        initiativeIndex.decrementAndGet()
        if (initiativeIndex.get() < 0) {
            initiativeIndex.set(sortedEntries.size - 1)
        }
    }

    val initiativeEmbedBuilder: EmbedBuilder
        get() {
            val embedBuilder = EmbedBuilder()
            embedBuilder.setColor(Color.GREEN)
            embedBuilder.setTitle("Initiative Order")
            val description = sortedEntries.withIndex().joinToString("\n") { (index, entry) ->
                "${index + initiativeIndex.get() % sortedEntries.size}: ${entry.name}: ${entry.roll}"
            }
            embedBuilder.setDescription(description)
            return embedBuilder
        }

    fun snapshot(): InitiativeStateSnapshot = InitiativeStateSnapshot(
        initiativeIndex = initiativeIndex.get(),
        entries = sortedEntries.map {
            InitiativeEntry(it.name, it.roll, it.kind, it.maxHp, it.currentHp, it.ac, it.defeated, it.templateId)
        }
    )

    fun restoreFrom(snapshot: InitiativeStateSnapshot) {
        initiativeIndex.set(snapshot.initiativeIndex)
        sortedEntries = LinkedList(snapshot.entries.map {
            RolledEntry(it.name, it.roll, it.kind, it.maxHp, it.currentHp, it.ac, it.defeated, it.templateId)
        })
    }

    /**
     * Mutate a single entry in-place. Used by the web combat endpoints to
     * apply damage without replacing the whole state. No-op if name isn't
     * present.
     */
    internal fun updateEntry(name: String, transform: (RolledEntry) -> RolledEntry) {
        val idx = sortedEntries.indexOfFirst { it.name == name }
        if (idx < 0) return
        sortedEntries[idx] = transform(sortedEntries[idx])
    }

    internal fun findByName(name: String): RolledEntry? =
        sortedEntries.firstOrNull { it.name == name }

    /**
     * Restore [amount] HP to [name], clamped to maxHp. If the entry was
     * defeated and the heal brings HP above 0, the defeated flag is cleared.
     * No-op when the name isn't present or the entry has no HP tracked.
     */
    internal fun applyHeal(name: String, amount: Int) {
        val existing = findByName(name) ?: return
        val max = existing.maxHp ?: return
        val base = existing.currentHp ?: 0
        val newHp = (base + amount).coerceAtMost(max)
        val revived = existing.defeated && newHp > 0
        updateEntry(name) {
            it.copy(
                currentHp = newHp,
                defeated = if (revived) false else it.defeated
            )
        }
    }

}

/**
 * A single rolled initiative entry. [kind] is `"PLAYER"` or `"MONSTER"` when set
 * by the web composer; nullable so legacy Discord-originated rolls still work.
 * HP + AC + defeated are populated during web combat; nullable for entries that
 * never entered combat state (e.g. Discord voice-channel rolls).
 */
data class RolledEntry(
    val name: String = "",
    val roll: Int = 0,
    val kind: String? = null,
    val maxHp: Int? = null,
    val currentHp: Int? = null,
    val ac: Int? = null,
    val defeated: Boolean = false,
    val templateId: Long? = null
)

/** JSON-friendly snapshot of an [InitiativeState], persisted in CampaignDto.state. */
data class InitiativeStateSnapshot(
    val initiativeIndex: Int = 0,
    val entries: List<InitiativeEntry> = emptyList()
)

data class InitiativeEntry(
    val name: String = "",
    val roll: Int = 0,
    val kind: String? = null,
    val maxHp: Int? = null,
    val currentHp: Int? = null,
    val ac: Int? = null,
    val defeated: Boolean = false,
    val templateId: Long? = null
)
