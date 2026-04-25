-- Toby Coin trade ledger.
--
-- Until now, /tobycoin buy|sell only mutated user balances and appended a
-- price sample to toby_coin_price_history. The web Market chart could
-- show price moves but had no way to surface "who did what" — markers
-- on the chart and a Recent trades list both require a per-trade record.
--
-- One row per executed trade. price_per_coin is captured BEFORE the
-- trade's pressure is applied to the market, so the marker reflects
-- the price the trader actually transacted at, not the post-trade
-- new price. Retained for 30 days (privacy.html mentions trade history
-- by username; longer retention would outlive the disclosure).

CREATE TABLE toby_coin_trade (
    id              BIGSERIAL        PRIMARY KEY,
    guild_id        BIGINT           NOT NULL,
    discord_id      BIGINT           NOT NULL,
    side            TEXT             NOT NULL CHECK (side IN ('BUY', 'SELL')),
    amount          BIGINT           NOT NULL CHECK (amount > 0),
    price_per_coin  DOUBLE PRECISION NOT NULL,
    executed_at     TIMESTAMPTZ      NOT NULL
);

CREATE INDEX idx_toby_coin_trade_guild_time
    ON toby_coin_trade (guild_id, executed_at DESC);

CREATE INDEX idx_toby_coin_trade_executed
    ON toby_coin_trade (executed_at);
