-- Opt-in game-activity tracking.
--
-- Collection is gated on two switches: a per-guild `ACTIVITY_TRACKING`
-- config key (owner-controlled) and a per-user `activity_tracking_opt_out`
-- flag. Both must allow the write for a session row to land.
--
-- Only Discord activities of type PLAYING are recorded. LISTENING,
-- STREAMING, WATCHING, and custom statuses are intentionally ignored.

-- Raw open/close sessions. Retained for 30 days so they can be rolled up
-- into the monthly aggregate below; older rows are purged by the
-- retention job.
CREATE TABLE activity_session (
    id            BIGSERIAL PRIMARY KEY,
    discord_id    BIGINT      NOT NULL,
    guild_id      BIGINT      NOT NULL,
    activity_name TEXT        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ
);
CREATE INDEX idx_activity_session_open
    ON activity_session (discord_id, guild_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_activity_session_started
    ON activity_session (guild_id, started_at);

-- Monthly per-user per-activity rollup. Kept for 12 months.
CREATE TABLE activity_monthly_rollup (
    discord_id    BIGINT      NOT NULL,
    guild_id      BIGINT      NOT NULL,
    month_start   DATE        NOT NULL,
    activity_name TEXT        NOT NULL,
    seconds       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, month_start, activity_name)
);
CREATE INDEX idx_activity_monthly_rollup_guild_month
    ON activity_monthly_rollup (guild_id, month_start);

-- Per-user opt-out. Default false (i.e. not opted out) so that when a
-- guild owner enables tracking and a user sees the notification, they
-- are covered until they explicitly opt out.
ALTER TABLE public."user"
    ADD COLUMN activity_tracking_opt_out BOOLEAN NOT NULL DEFAULT FALSE;
