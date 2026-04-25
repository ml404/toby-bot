-- Per-guild jackpot pool fed by Toby Coin trade fees.
--
-- Every buy and sell pays a small flat fee (TRADE_FEE in
-- TobyCoinEngine) that lands here instead of dissolving into the
-- market. Casino minigame wins roll a small probability of hitting
-- the pool — on a hit, the player banks the entire pool and the
-- counter resets to zero.
--
-- The pool is per-guild because the rest of the economy is per-guild
-- (market price, trade history, balances) — fees in one server
-- shouldn't show up as a payout in another.

CREATE TABLE toby_coin_jackpot (
    guild_id BIGINT PRIMARY KEY,
    pool     BIGINT NOT NULL DEFAULT 0 CHECK (pool >= 0)
);
