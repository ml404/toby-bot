package common.events.activity

/**
 * Fact emitted by `VoiceSessionService.closeSession` whenever a voice
 * session is closed and credited. [countedSeconds] is the wall-clock
 * time the user actually had company in the voice channel — the same
 * value used to compute voice credits and XP. Subscribers (achievements)
 * progress voice-hour milestones against this delta.
 */
data class VoiceSessionLoggedEvent(
    val discordId: Long,
    val guildId: Long,
    val countedSeconds: Long
)
