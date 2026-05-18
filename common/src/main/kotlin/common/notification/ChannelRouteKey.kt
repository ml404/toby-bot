package common.notification

/**
 * Per-kind channel-routing policy used by `NotificationRouter.sendChannel`.
 * Each entry declares which per-guild config key holds the channel id
 * (and any fallbacks) so admins control channel visibility by setting
 * (or clearing) a single config row — not by editing code.
 *
 * Config-key names are stored as plain strings here so this enum stays
 * in `common` and doesn't pull `database.dto.ConfigDto` into the
 * downstream dependency graph. The
 * `ChannelRouteKeyConfigKeyContractTest` (in `:database`) asserts every
 * string resolves to a real `ConfigDto.Configurations` value at compile
 * time of the test, so a typo here fails CI.
 *
 * Resolution order applied by the router for any given route:
 *   1. `primaryConfigKey` (if set and resolvable to a writable channel)
 *   2. each `fallbackConfigKeys` in declaration order
 *   3. `originChannelId` (the per-call hint, e.g. "the channel that
 *      earned the XP" for level-up announcements)
 *   4. `guild.systemChannel` (only when [systemChannelFallback] is true)
 *
 * Channel routing does NOT consult `UserNotificationPrefService`. Admins
 * disable a channel post by clearing the configured channel id; user
 * opt-ins govern DM dispatch only.
 */
enum class ChannelRouteKey(
    val primaryConfigKey: String?,
    val fallbackConfigKeys: List<String> = emptyList(),
    val systemChannelFallback: Boolean = true,
) {
    /** Level-up announcement. Falls back to the XP-earning channel, then system. */
    LEVEL_UP(primaryConfigKey = "LEVEL_UP_CHANNEL"),

    /**
     * Daily lottery announcement. Reuses the configured leaderboard
     * channel if no dedicated lottery channel is set — most guilds wire
     * one announce channel and expect both to land there.
     */
    LOTTERY(
        primaryConfigKey = "LOTTERY_CHANNEL",
        fallbackConfigKeys = listOf("LEADERBOARD_CHANNEL"),
    ),

    /**
     * Optional public shoutout when a user unlocks an achievement.
     * No system-channel fallback: admins explicitly opt in by setting
     * the channel; if unset, unlocks stay DM-only.
     */
    ACHIEVEMENT_SHOUTOUT(
        primaryConfigKey = "ACHIEVEMENT_ANNOUNCE_CHANNEL",
        systemChannelFallback = false,
    ),

    /**
     * Direct-to-system-channel routing used by web-initiated flows
     * (tip notifications, duel offers) that have no per-event channel
     * context to fall back to.
     */
    SYSTEM(primaryConfigKey = null),
}
