package common.events

/**
 * Fact emitted via Spring's [org.springframework.context.ApplicationEventPublisher]
 * whenever an XP award pushes a user past one or more level thresholds.
 * Subscribers handle the consequences: posting an announcement in the
 * originating channel, assigning configured role rewards, and unlocking
 * any titles gated by `required_level`.
 *
 * [channelId] is the Discord text-channel id where the triggering action
 * happened (e.g. the message that earned the XP, the slash-command channel).
 * It is null for voice-derived awards — listeners should fall back to the
 * configured `LEVEL_UP_CHANNEL` or the guild's system channel in that case.
 */
data class LevelUpEvent(
    val discordId: Long,
    val guildId: Long,
    val oldLevel: Int,
    val newLevel: Int,
    val channelId: Long?
)
