package common.notification

/**
 * Catalogue of user-facing notification channels the bot routes through
 * [bot.toby.notify.NotificationRouter]. The [defaultOptIn] flag is the
 * fallback used when the user has no row in `user_notification_pref` for
 * that kind — picked per-channel so existing DM behaviour (duels, tips,
 * intro prompt) is preserved by default while opt-in is required for
 * noisier new channels (price alerts, streak reminders).
 *
 * The display name is what shows up in `/notify list` and the web
 * preferences UI; the description explains the trigger to the end user.
 */
enum class NotificationChannelKind(
    val displayName: String,
    val description: String,
    val defaultOptIn: Boolean
) {
    DUEL_OFFER(
        displayName = "Duel offers",
        description = "Someone challenges you to a duel from the web dashboard.",
        defaultOptIn = true
    ),
    TIP_RECEIVED(
        displayName = "Tips received",
        description = "Another user tips you social credit from the web dashboard.",
        defaultOptIn = true
    ),
    LEVEL_UP_DM(
        displayName = "Level-up DM",
        description = "Off by default — level-ups already post in the configured channel.",
        defaultOptIn = false
    ),
    ACHIEVEMENT_UNLOCK(
        displayName = "Achievement unlocks",
        description = "DM when you unlock a new achievement.",
        defaultOptIn = true
    ),
    STREAK_REMINDER(
        displayName = "Daily streak reminder",
        description = "Off by default — nudge before your streak resets at midnight UTC.",
        defaultOptIn = false
    ),
    LOTTERY_DRAW_WITH_MY_TICKET(
        displayName = "Lottery draw (your ticket)",
        description = "DM when a daily lottery you bought a ticket for is drawn.",
        defaultOptIn = true
    ),
    PRICE_ALERT(
        displayName = "TobyCoin price alert",
        description = "Off by default — DM on large TobyCoin price moves.",
        defaultOptIn = false
    ),
    INTRO_PROMPT(
        displayName = "Intro setup prompt",
        description = "First-time prompt to set your voice-channel intro song.",
        defaultOptIn = true
    );

    companion object {
        fun fromCode(code: String): NotificationChannelKind? =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
    }
}
