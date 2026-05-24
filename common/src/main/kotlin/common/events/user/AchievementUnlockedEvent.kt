package common.events.user

/**
 * Fact emitted by `AchievementService.unlock` whenever a user crosses an
 * achievement threshold for the first time. Idempotent — the service
 * guards against re-publishing for already-unlocked achievements, so
 * subscribers can assume one event per (user, achievement) ever.
 *
 * [achievementCode] is the stable referent (e.g. "streak_7"); [name] is
 * the display label. The DM/notify router uses both — code for routing
 * logic, name for embed text. [channelId] is the originating Discord
 * channel when the unlock was triggered by a slash command path; null
 * for background/voice/scheduled triggers.
 */
data class AchievementUnlockedEvent(
    val discordId: Long,
    val guildId: Long,
    val achievementId: Long,
    val achievementCode: String,
    val name: String,
    val description: String,
    val icon: String?,
    val channelId: Long?
)
