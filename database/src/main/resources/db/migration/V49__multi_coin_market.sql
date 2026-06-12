-- Multi-coin fake market.
--
-- Until now every guild ran exactly one market ("TOBY"), keyed by guild_id
-- alone. This migration turns each guild's market into one-per-coin so the
-- bot can offer a spread of risk appetites (a sleepy near-stablecoin, the
-- baseline TOBY, a mid-cap, and a degen memecoin) and give traders more
-- reason to watch the chart. See common.economy.Coin for the catalogue.
--
-- Strategy: every existing row is a TOBY row, so we add a `coin` column
-- defaulting to 'TOBY' and widen the keys/indexes to include it. TOBY
-- balances stay in user.toby_coins (the rest of the bot settles in TOBY);
-- the new coins get their own user_coin_holding table.

-- --- Market: one row per (guild, coin) -----------------------------------
ALTER TABLE toby_coin_market
    ADD COLUMN coin VARCHAR(16) NOT NULL DEFAULT 'TOBY';
ALTER TABLE toby_coin_market
    DROP CONSTRAINT toby_coin_market_pkey;
ALTER TABLE toby_coin_market
    ADD CONSTRAINT toby_coin_market_pkey PRIMARY KEY (guild_id, coin);

-- --- Price history: chart samples per (guild, coin) ----------------------
ALTER TABLE toby_coin_price_history
    ADD COLUMN coin VARCHAR(16) NOT NULL DEFAULT 'TOBY';
DROP INDEX IF EXISTS idx_toby_price_history_guild_time;
CREATE INDEX idx_toby_price_history_guild_coin_time
    ON toby_coin_price_history (guild_id, coin, sampled_at DESC);

-- --- Trade ledger: per (guild, coin) -------------------------------------
ALTER TABLE toby_coin_trade
    ADD COLUMN coin VARCHAR(16) NOT NULL DEFAULT 'TOBY';
DROP INDEX IF EXISTS idx_toby_coin_trade_guild_time;
CREATE INDEX idx_toby_coin_trade_guild_coin_time
    ON toby_coin_trade (guild_id, coin, executed_at DESC);
-- idx_toby_coin_trade_executed (prune scan) is unchanged.

-- --- Price triggers: armed per (guild, coin) -----------------------------
ALTER TABLE user_price_trigger
    ADD COLUMN coin VARCHAR(16) NOT NULL DEFAULT 'TOBY';
DROP INDEX IF EXISTS idx_user_price_trigger_guild_enabled;
CREATE INDEX idx_user_price_trigger_guild_coin_enabled
    ON user_price_trigger (guild_id, coin) WHERE enabled;

-- --- Per-user holdings for the NON-TOBY coins ----------------------------
-- TOBY balances remain in user.toby_coins so titles / casino / leaderboard
-- keep working untouched. Everything else a user holds lives here.
CREATE TABLE user_coin_holding (
    discord_id BIGINT      NOT NULL,
    guild_id   BIGINT      NOT NULL,
    coin       VARCHAR(16) NOT NULL,
    amount     BIGINT      NOT NULL DEFAULT 0 CHECK (amount >= 0),
    PRIMARY KEY (discord_id, guild_id, coin)
);
