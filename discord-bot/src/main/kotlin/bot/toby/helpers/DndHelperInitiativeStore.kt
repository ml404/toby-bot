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
            entries.map {
                RolledEntry(
                    name = it.name,
                    roll = it.roll,
                    kind = it.kind,
                    maxHp = it.maxHp,
                    currentHp = it.currentHp,
                    ac = it.ac,
                    defeated = it.defeated,
                    templateId = it.templateId
                )
            }
        )
    }

    override fun currentEntries(guildId: Long): List<InitiativeEntryData> =
        dndHelper.stateFor(guildId).sortedEntries.map(::toData)

    override fun currentIndex(guildId: Long): Int =
        dndHelper.stateFor(guildId).initiativeIndex.get()

    override fun isActive(guildId: Long): Boolean =
        dndHelper.stateFor(guildId).isActive()

    override fun applyDamage(guildId: Long, targetName: String, damage: Int): InitiativeEntryData? {
        val state = dndHelper.stateFor(guildId)
        if (!state.isActive()) return null
        val existing = state.findByName(targetName) ?: return null
        val newHp = existing.currentHp?.let { (it - damage).coerceAtLeast(0) }
        val defeated = existing.defeated || (newHp != null && newHp <= 0)
        state.updateEntry(targetName) { it.copy(currentHp = newHp, defeated = defeated) }
        return toData(state.findByName(targetName) ?: return null)
    }

    override fun applyHeal(guildId: Long, targetName: String, amount: Int): InitiativeEntryData? {
        val state = dndHelper.stateFor(guildId)
        if (!state.isActive()) return null
        val existing = state.findByName(targetName) ?: return null
        if (existing.maxHp == null) return null
        state.applyHeal(targetName, amount)
        return toData(state.findByName(targetName) ?: return null)
    }

    private fun toData(entry: RolledEntry) = InitiativeEntryData(
        name = entry.name,
        roll = entry.roll,
        kind = entry.kind,
        maxHp = entry.maxHp,
        currentHp = entry.currentHp,
        ac = entry.ac,
        defeated = entry.defeated,
        templateId = entry.templateId
    )
}
