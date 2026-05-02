-- Append-only audit log for every settled blackjack hand.
-- Mirrors poker_hand_log: a row is written exactly once per resolved
-- /blackjack hand (solo or multi). Captures the per-seat outcome and
-- the hand-level totals (pot, rake) for chart/audit purposes.
--
-- For SOLO hands, `players`, `seat_results`, and `payouts` always have
-- exactly one entry. For MULTI hands they list every seat at the moment
-- of resolution, including seats that were dropped post-hand via
-- pendingLeave (those still settled this hand before being removed).
--
-- `seat_results` is a comma-separated `discord_id:RESULT` list, with
-- RESULT one of PLAYER_BLACKJACK / PLAYER_WIN / PUSH / DEALER_WIN /
-- PLAYER_BUST. `payouts` is the same shape with credit amounts.
CREATE TABLE blackjack_hand_log (
    id            BIGSERIAL    PRIMARY KEY,
    guild_id      BIGINT       NOT NULL,
    table_id      BIGINT       NOT NULL,
    hand_number   BIGINT       NOT NULL,
    mode          VARCHAR(8)   NOT NULL,            -- 'SOLO' or 'MULTI'
    players       VARCHAR(512) NOT NULL,
    dealer        VARCHAR(64)  NOT NULL,            -- e.g. "K♠,7♥,A♦"
    dealer_total  INT          NOT NULL,
    seat_results  VARCHAR(512) NOT NULL,
    payouts       VARCHAR(512) NOT NULL,
    pot           BIGINT       NOT NULL CHECK (pot >= 0),
    rake          BIGINT       NOT NULL DEFAULT 0 CHECK (rake >= 0),
    resolved_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_blackjack_hand_log_guild_time ON blackjack_hand_log (guild_id, resolved_at DESC);
CREATE INDEX idx_blackjack_hand_log_table      ON blackjack_hand_log (table_id, hand_number);
