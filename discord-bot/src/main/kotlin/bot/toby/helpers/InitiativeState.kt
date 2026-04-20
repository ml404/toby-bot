package bot.toby.helpers

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
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
    var sortedEntries: LinkedList<Map.Entry<String, Int>> = LinkedList()
        private set

    fun isActive(): Boolean = sortedEntries.isNotEmpty()

    internal fun sortMap(initiativeMap: Map<String, Int>) {
        sortedEntries = LinkedList(initiativeMap.entries.sortedByDescending { it.value })
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
                "${index + initiativeIndex.get() % sortedEntries.size}: ${entry.key}: ${entry.value}"
            }
            embedBuilder.setDescription(description)
            return embedBuilder
        }

    fun snapshot(): InitiativeStateSnapshot = InitiativeStateSnapshot(
        initiativeIndex = initiativeIndex.get(),
        entries = sortedEntries.map { InitiativeEntry(it.key, it.value) }
    )

    fun restoreFrom(snapshot: InitiativeStateSnapshot) {
        initiativeIndex.set(snapshot.initiativeIndex)
        sortedEntries = LinkedList(snapshot.entries.map { java.util.AbstractMap.SimpleEntry(it.name, it.roll) })
    }
}

/** JSON-friendly snapshot of an [InitiativeState], persisted in CampaignDto.state. */
data class InitiativeStateSnapshot(
    val initiativeIndex: Int = 0,
    val entries: List<InitiativeEntry> = emptyList()
)

data class InitiativeEntry(
    val name: String = "",
    val roll: Int = 0
)
