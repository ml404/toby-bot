-- Rename the NotificationChannelKind value LEVEL_UP_DM -> LEVEL_UP.
-- The enum is widening from DM-only to DM+CHANNEL+PUSH so the channel
-- post (now passing ChannelMentions) can gate per-user `<@id>` pings
-- on (LEVEL_UP, Surface.CHANNEL) just like ACHIEVEMENT_UNLOCK.
--
-- Existing rows in user_notification_pref carry a stringified enum
-- name in channel_kind, so we rewrite the literal in-place. Any
-- per-user opt-out of the level-up DM is preserved exactly.
UPDATE user_notification_pref
SET channel_kind = 'LEVEL_UP'
WHERE channel_kind = 'LEVEL_UP_DM';
