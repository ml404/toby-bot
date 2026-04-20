package bot.toby.helpers

import org.springframework.stereotype.Service
import web.service.InitiativeEntryData
import web.service.InitiativeStore

/**
 * Default [InitiativeStore] implementation that proxies to [DnDHelper]. Lets
 * the web module seed and read the per-guild initiative tracker without taking
 * a compile-time dependency on discord-bot's Discord types.
 */
@Service
class DndHelperInitiativeStore(
    private val dndHelper: DnDHelper
) : InitiativeStore {

    override fun seed(guildId: Long, entries: List<InitiativeEntryData>) {
        dndHelper.seedInitiative(
            guildId,
            entries.map { RolledEntry(name = it.name, roll = it.roll, kind = it.kind) }
        )
    }

    override fun currentEntries(guildId: Long): List<InitiativeEntryData> =
        dndHelper.stateFor(guildId).sortedEntries.map {
            InitiativeEntryData(name = it.name, roll = it.roll, kind = it.kind)
        }

    override fun currentIndex(guildId: Long): Int =
        dndHelper.stateFor(guildId).initiativeIndex.get()

    override fun isActive(guildId: Long): Boolean =
        dndHelper.stateFor(guildId).isActive()
}
