package common.events

/**
 * Fact emitted via Spring's [org.springframework.context.ApplicationEventPublisher]
 * whenever the ACTIVITY_TRACKING guild config is set to `true` — whether via the
 * Discord `/setconfig activity_tracking on` command or the web moderation UI.
 *
 * The listener ([bot.toby.activity.ActivityTrackingNotifier.onActivityTrackingEnabled])
 * resolves the guild from [guildId] and runs the first-enable notification; that
 * flow is internally idempotent via the `ACTIVITY_TRACKING_NOTIFIED` flag, so
 * publishers don't need to de-duplicate across false→true vs true→true writes.
 */
data class ActivityTrackingEnabled(
    val guildId: Long
)
