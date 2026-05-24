package common.events.casino.keno

/**
 * Fact emitted by `KenoService` whenever a hand resolves with every
 * picked number drawn (`hits == picks.size`). Subscribers (achievements)
 * unlock the `keno_first_perfect` catalog entry.
 */
data class KenoPerfectEvent(
    val discordId: Long,
    val guildId: Long,
)
