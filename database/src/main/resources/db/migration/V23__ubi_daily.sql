-- Idempotency ledger for the daily Universal Basic Income grant. One row
-- per (user, guild, UTC date) ensures the scheduled job can be retried or
-- the bot can restart without double-granting the same day's UBI.
CREATE TABLE ubi_daily (
    discord_id      BIGINT NOT NULL,
    guild_id        BIGINT NOT NULL,
    grant_date      DATE   NOT NULL,
    credits_granted BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, grant_date)
);
