-- Social/PvP credit-spend ledgers and per-day outgoing-tip cap.
-- Tips are transfers (not earnings) and must NOT route through the
-- voice_credit_daily bucket — otherwise alts could launder daily-capped
-- earnings. tip_daily is a separate per-sender per-day ledger.

-- Per-sender per-guild per-day total tipped out. Pessimistically locked
-- inside TipService.tip() so concurrent /tip invocations from the same
-- sender can't both clear the cap check and double-debit.
CREATE TABLE tip_daily (
    sender_discord_id BIGINT NOT NULL,
    guild_id          BIGINT NOT NULL,
    tip_date          DATE   NOT NULL,
    credits_sent      BIGINT NOT NULL DEFAULT 0 CHECK (credits_sent >= 0),
    PRIMARY KEY (sender_discord_id, guild_id, tip_date)
);

-- Append-only audit log for every successful tip.
CREATE TABLE tip_log (
    id                   BIGSERIAL  PRIMARY KEY,
    guild_id             BIGINT     NOT NULL,
    sender_discord_id    BIGINT     NOT NULL,
    recipient_discord_id BIGINT     NOT NULL,
    amount               BIGINT     NOT NULL CHECK (amount > 0),
    note                 TEXT,
    created_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tip_log_guild_time   ON tip_log (guild_id, created_at DESC);
CREATE INDEX idx_tip_log_sender_time  ON tip_log (sender_discord_id, guild_id, created_at DESC);

-- Append-only audit log for every accepted duel resolution.
-- Decline/timeout produce no row (no credit movement to record).
CREATE TABLE duel_log (
    id                    BIGSERIAL  PRIMARY KEY,
    guild_id              BIGINT     NOT NULL,
    initiator_discord_id  BIGINT     NOT NULL,
    opponent_discord_id   BIGINT     NOT NULL,
    winner_discord_id     BIGINT     NOT NULL,
    loser_discord_id      BIGINT     NOT NULL,
    stake                 BIGINT     NOT NULL CHECK (stake > 0),
    pot                   BIGINT     NOT NULL CHECK (pot > 0),
    loss_tribute          BIGINT     NOT NULL DEFAULT 0 CHECK (loss_tribute >= 0),
    resolved_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_duel_log_guild_time ON duel_log (guild_id, resolved_at DESC);
