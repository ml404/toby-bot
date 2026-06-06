-- Append-only ledger of bot install lifecycle events: one row per
-- GuildJoinEvent (JOIN) and GuildLeaveEvent (LEAVE). Unlike the config
-- sentinel (INSTALL_MODE / INSTALLED_AT), which is a current-state
-- snapshot keyed by guild, this is an event log so the operator dashboard
-- can show churn (joins vs. leaves) and growth over time. Only captures
-- events from this migration's deploy onward — there is no retroactive
-- history for guilds that joined before the ledger existed.
CREATE TABLE IF NOT EXISTS install_event (
    id          BIGSERIAL PRIMARY KEY,
    guild_id    BIGINT      NOT NULL,
    event_type  VARCHAR(8)  NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- The dashboard's time-bucketed queries (events per month, counts since a
-- cutoff) all filter/scan on occurred_at.
CREATE INDEX IF NOT EXISTS idx_install_event_occurred_at
    ON install_event (occurred_at);
