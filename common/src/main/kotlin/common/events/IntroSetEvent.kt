package common.events

/**
 * Fact emitted by `MusicFileService.createNewMusicFile` when a user
 * saves an intro song. Fires on every create (idempotent unlock on
 * the achievement side ensures one-shot semantics).
 */
data class IntroSetEvent(
    val discordId: Long,
    val guildId: Long
)
