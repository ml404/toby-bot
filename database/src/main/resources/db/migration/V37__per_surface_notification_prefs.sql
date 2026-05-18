-- Per-surface notification preferences. Today's user_notification_pref
-- is keyed by (discord_id, guild_id, channel_kind) — one row per (user,
-- kind), effectively a DM-only opt-in flag (sendChannel never consulted
-- the table). Extending the PK with a `surface` column lets a single
-- user hold distinct (kind, DM), (kind, CHANNEL), and (kind, PUSH)
-- preferences — e.g. "DM me when I unlock an achievement, don't
-- shoutout publicly".

-- 1. Add the column with a DM backfill default. Matches today's
--    effective behaviour exactly: every existing row was a DM
--    preference; no CHANNEL or PUSH row would ever have been created
--    because the dispatch paths didn't consult the table.
ALTER TABLE user_notification_pref
    ADD COLUMN surface VARCHAR(16) NOT NULL DEFAULT 'DM';

-- 2. Swap PK to include surface so a single user holds independent
--    rows per (kind, surface). Postgres lets us drop + add the PK in
--    one transaction; existing rows already carry the backfilled
--    surface so the new PK is satisfied.
ALTER TABLE user_notification_pref DROP CONSTRAINT user_notification_pref_pkey;
ALTER TABLE user_notification_pref
    ADD PRIMARY KEY (discord_id, guild_id, channel_kind, surface);

-- 3. Drop the column DEFAULT so new inserts must declare a surface
--    explicitly. Otherwise a future caller that forgets the surface
--    arg silently lands on DM and we never notice the bug.
ALTER TABLE user_notification_pref ALTER COLUMN surface DROP DEFAULT;
