-- Freeze each user's NON-TOBY coin balances at the monthly boundary so the
-- wallet leaderboard can show a per-coin "+/- this month" on every holding,
-- not just TOBY. TOBY itself keeps living in monthly_credit_snapshot.toby_coins
-- (the rest of the bot settles in TOBY); this table mirrors user_coin_holding
-- with a snapshot_date, one row per (user, guild, month, coin).
--
-- Missing coins read as a 0 baseline, which is the correct bootstrap: a coin a
-- user starts holding mid-month shows the full amount as "gained this month",
-- and the next boundary freeze settles it.
CREATE TABLE monthly_coin_holding_snapshot (
    discord_id    BIGINT      NOT NULL,
    guild_id      BIGINT      NOT NULL,
    snapshot_date DATE        NOT NULL,
    coin          VARCHAR(16) NOT NULL,
    amount        BIGINT      NOT NULL DEFAULT 0 CHECK (amount >= 0),
    PRIMARY KEY (discord_id, guild_id, snapshot_date, coin)
);

-- The leaderboard reads a whole guild's baseline for one month in a single
-- query, so index the lookup key.
CREATE INDEX idx_monthly_coin_holding_snapshot_guild_date
    ON monthly_coin_holding_snapshot (guild_id, snapshot_date);
