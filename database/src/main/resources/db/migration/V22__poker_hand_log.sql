-- Append-only audit log for every settled poker hand.
-- A hand row is written exactly once per /poker hand resolution (showdown
-- OR everyone-folded short-circuit). The actual chip movements happen
-- on PokerTable seats in memory; the user.social_credit balance is only
-- touched at /poker buy-in (debit) and /poker leave (credit remaining
-- chips). This log captures the hand-level outcome for a chart/audit.
--
-- `players` and `winners` are comma-separated discord IDs. Multiple
-- winners only appear on chops where two contenders' best 5-card hands
-- are exactly equal.
CREATE TABLE poker_hand_log (
    id           BIGSERIAL    PRIMARY KEY,
    guild_id     BIGINT       NOT NULL,
    table_id     BIGINT       NOT NULL,
    hand_number  BIGINT       NOT NULL,
    players      VARCHAR(512) NOT NULL,
    winners      VARCHAR(512) NOT NULL,
    pot          BIGINT       NOT NULL CHECK (pot >= 0),
    rake         BIGINT       NOT NULL DEFAULT 0 CHECK (rake >= 0),
    board        VARCHAR(64)  NOT NULL,
    resolved_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_poker_hand_log_guild_time ON poker_hand_log (guild_id, resolved_at DESC);
CREATE INDEX idx_poker_hand_log_table      ON poker_hand_log (table_id, hand_number);
