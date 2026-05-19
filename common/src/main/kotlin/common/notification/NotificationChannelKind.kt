package common.notification

/**
 * Catalogue of user-facing notification channels the bot routes through
 * [bot.toby.notify.NotificationRouter]. Each kind declares which
 * [Surface]s it supports and the default opt-in for each — a user with
 * no explicit `user_notification_pref` row for a `(kind, surface)` pair
 * falls back to that default.
 *
 * Per-surface defaults preserve today's behaviour exactly:
 * - CHANNEL defaults match the pre-refactor "channel post always pings"
 *   behaviour (every notifier that ever set `<@id>` in content kept its
 *   recipient/opponent/winner ping by default).
 * - DM defaults match the pre-refactor `defaultOptIn` flag.
 * - PUSH defaults to off across the board: an adapter PR can ship
 *   without surprising any user with unrequested pushes.
 *
 * The display name is what shows up in `/notify list` and the web
 * preferences UI; the description explains the trigger to the end user.
 */
enum class NotificationChannelKind(
    val displayName: String,
    val description: String,
    val perSurfaceDefaults: Map<Surface, Boolean>,
) {
    DUEL_OFFER(
        displayName = "Duel offers",
        description = "Someone challenges you to a duel from the web dashboard.",
        perSurfaceDefaults = mapOf(Surface.CHANNEL to true, Surface.PUSH to false),
    ),
    TIP_RECEIVED(
        displayName = "Tips received",
        description = "Another user tips you social credit from the web dashboard.",
        perSurfaceDefaults = mapOf(Surface.CHANNEL to true, Surface.PUSH to false),
    ),
    LEVEL_UP(
        displayName = "Level-ups",
        description = "Channel shoutout when you level up; optional DM (off by default) and push.",
        perSurfaceDefaults = mapOf(Surface.DM to false, Surface.CHANNEL to true, Surface.PUSH to false),
    ),
    ACHIEVEMENT_UNLOCK(
        displayName = "Achievement unlocks",
        description = "DM when you unlock a new achievement; optional public shoutout.",
        perSurfaceDefaults = mapOf(Surface.DM to true, Surface.CHANNEL to true, Surface.PUSH to false),
    ),
    STREAK_REMINDER(
        displayName = "Daily streak reminder",
        description = "Off by default — nudge before your streak resets at midnight UTC.",
        perSurfaceDefaults = mapOf(Surface.DM to false, Surface.PUSH to false),
    ),
    LOTTERY_DRAW_WITH_MY_TICKET(
        displayName = "Lottery draw (your ticket)",
        description = "Channel ping when a daily lottery you bought a ticket for is drawn.",
        perSurfaceDefaults = mapOf(Surface.CHANNEL to true, Surface.PUSH to false),
    ),
    PRICE_ALERT(
        displayName = "TobyCoin price alert",
        description = "Off by default — DM receipts when a /pricealert trigger auto-executes a buy or sell on your behalf.",
        perSurfaceDefaults = mapOf(Surface.DM to false, Surface.PUSH to false),
    ),
    INTRO_PROMPT(
        displayName = "Intro setup prompt",
        description = "First-time prompt to set your voice-channel intro song.",
        // DM-only: the prompt opens an interactive EventWaiter flow,
        // which doesn't translate to a one-shot push notification.
        perSurfaceDefaults = mapOf(Surface.DM to true),
    );

    /** Surfaces this kind ships on. Derived from [perSurfaceDefaults]. */
    val supportedSurfaces: Set<Surface> get() = perSurfaceDefaults.keys

    /** Default opt-in for [surface]. Returns `false` for unsupported surfaces (defensive). */
    fun defaultOptIn(surface: Surface): Boolean = perSurfaceDefaults[surface] ?: false

    /** Whether [surface] is a valid delivery surface for this kind. */
    fun supports(surface: Surface): Boolean = surface in perSurfaceDefaults

    companion object {
        fun fromCode(code: String): NotificationChannelKind? =
            entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
    }
}
