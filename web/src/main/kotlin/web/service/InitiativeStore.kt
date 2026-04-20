package web.service

/**
 * Thin abstraction over the per-guild initiative tracker owned by discord-bot's
 * `DnDHelper`. Lives in the web module because web can't depend on discord-bot
 * directly; discord-bot provides the concrete implementation that proxies to
 * `DnDHelper` so both modules read/write the same state.
 */
interface InitiativeStore {

    /**
     * Overwrite the guild's initiative with [entries]. Entries are sorted
     * descending by roll before being installed; index resets to 0.
     */
    fun seed(guildId: Long, entries: List<InitiativeEntryData>)

    /** Current sorted entries for [guildId], or empty if no active tracker. */
    fun currentEntries(guildId: Long): List<InitiativeEntryData>

    /** 0-based index of whose turn it is, or 0 when the tracker is empty. */
    fun currentIndex(guildId: Long): Int

    fun isActive(guildId: Long): Boolean

    /** The entry whose turn it currently is, or null if no active tracker. */
    fun currentEntry(guildId: Long): InitiativeEntryData? =
        currentEntries(guildId).getOrNull(currentIndex(guildId))

    /**
     * Apply [damage] HP to the participant named [targetName] in [guildId]'s
     * tracker. If the target's HP drops to 0 or below, mark them defeated.
     * Returns the updated entry (with new currentHp / defeated state), or
     * null when there's no active tracker or no participant with that name.
     */
    fun applyDamage(guildId: Long, targetName: String, damage: Int): InitiativeEntryData?
}

/**
 * Module-agnostic rolled-entry payload. [kind] is `"PLAYER"` / `"MONSTER"` for
 * web-composed rolls and `null` for Discord-originated rolls.
 */
data class InitiativeEntryData(
    val name: String,
    val roll: Int,
    val kind: String? = null,
    val modifier: Int = 0,
    val maxHp: Int? = null,
    val currentHp: Int? = null,
    val ac: Int? = null,
    val defeated: Boolean = false
)
