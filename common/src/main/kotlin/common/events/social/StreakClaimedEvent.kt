package common.events.social

/**
 * Fact emitted by `LoginStreakService.claim` whenever a user actually
 * advances their daily streak (i.e. not a same-day re-claim). Mirrors
 * [LevelUpEvent]: plain data, published via
 * [org.springframework.context.ApplicationEventPublisher], subscribers
 * decide the consequences.
 *
 * [currentStreak] is the run-length *after* this claim (so day-1 claims
 * arrive as 1). [longestStreak] is the user's personal best after this
 * claim — equal to currentStreak when they're at a new high.
 *
 * [channelId] is the text-channel where `/daily` was invoked; null for
 * web-initiated claims (no Discord channel context). Subscribers that
 * need to post in Discord should fall back to the guild's system or
 * level-up channel in that case.
 */
data class StreakClaimedEvent(
    val discordId: Long,
    val guildId: Long,
    val currentStreak: Int,
    val longestStreak: Int,
    val channelId: Long?
)
