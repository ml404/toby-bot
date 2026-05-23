-- Per-guild per-day message counter feeding the moderation Activity tab
-- (messages-per-day chart). Composite-keyed on (guild_id, day_start) so
-- a single row per guild per UTC date is enough for the 30-day-rolling
-- view. Messages are buffered in memory and flushed in batches by
-- MessageActivityBuffer, so the write path is upserts (`INSERT ... ON
-- CONFLICT DO UPDATE SET count = count + EXCLUDED.count`) rather than a
-- per-message round trip. Hibernate handles the SELECT/UPDATE for now;
-- if write throughput becomes a hot spot, swap to a native upsert.
CREATE TABLE IF NOT EXISTS message_daily_count (
    guild_id   BIGINT NOT NULL,
    day_start  DATE   NOT NULL,
    count      BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_message_daily_count PRIMARY KEY (guild_id, day_start)
);

-- 30-day window queries filter on (guild_id, day_start >= since). The
-- composite primary key already serves this access path, but an
-- explicit btree on day_start helps Postgres choose an index-only scan
-- when the planner sees the date range first.
CREATE INDEX IF NOT EXISTS idx_message_daily_count_day_start
    ON message_daily_count (day_start);
