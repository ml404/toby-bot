-- Toby Coin economy.
--
-- A per-guild fake-cryptocurrency market. Users trade social credit for
-- Toby Coins through /tobycoin buy|sell. The price moves on two tracks:
--   1. A scheduled "random walk" tick (Geometric Brownian Motion) so the
--      price drifts around like a real stock even when idle.
--   2. Trade pressure: buys nudge the price up, sells nudge it down.
--
-- Every price change (tick or trade) appends a sample to
-- toby_coin_price_history so /tobycoin chart can render a stock chart.

-- Per-user coin balance.
ALTER TABLE public."user"
    ADD COLUMN toby_coins BIGINT NOT NULL DEFAULT 0;

-- One row per guild holding the current market price.
CREATE TABLE toby_coin_market (
    guild_id     BIGINT           PRIMARY KEY,
    price        DOUBLE PRECISION NOT NULL,
    last_tick_at TIMESTAMPTZ      NOT NULL
);

-- Price samples for charting. Retained for 30 days by the tick job.
CREATE TABLE toby_coin_price_history (
    id         BIGSERIAL        PRIMARY KEY,
    guild_id   BIGINT           NOT NULL,
    sampled_at TIMESTAMPTZ      NOT NULL,
    price      DOUBLE PRECISION NOT NULL
);
CREATE INDEX idx_toby_price_history_guild_time
    ON toby_coin_price_history (guild_id, sampled_at DESC);
