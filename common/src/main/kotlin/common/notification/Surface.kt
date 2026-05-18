package common.notification

/**
 * Delivery surface a notification can reach a user through. Used as the
 * second key on `user_notification_pref` (alongside [NotificationChannelKind])
 * so a single user can hold distinct preferences per (kind, surface) —
 * e.g. "DM me when I unlock an achievement but don't shoutout publicly".
 *
 * - [DM]: bot opens a private channel and sends a Discord DM.
 * - [CHANNEL]: bot posts in a guild text channel. Per-user opt-in
 *   doesn't decide whether the post happens (admins control that via
 *   channel-config keys), but it does decide whether the user's
 *   `<@id>` mention inside the post triggers a notification —
 *   `NotificationRouter.sendChannel` whitelists only opted-in users
 *   via JDA's `mentionUsers(...)`.
 * - [PUSH]: future provider-backed push notification (e.g. web push,
 *   Firebase). No adapter is wired today; the router has a no-op
 *   branch so opt-ins persist forward-compatibly. Default opt-in is
 *   off across the board so an adapter PR can ship without surprising
 *   any user.
 */
enum class Surface {
    DM,
    CHANNEL,
    PUSH,
}
