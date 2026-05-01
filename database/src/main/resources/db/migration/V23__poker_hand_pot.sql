-- Per-tier breakdown of a settled poker hand. v1 stored a single
-- `pot` column on `poker_hand_log`; v2's side-pot resolver can
-- split a hand into multiple tiers (one main pot + N side pots
-- when players go all-in for different amounts), and we want each
-- tier auditable on its own row instead of just the aggregate.
--
-- One row per side-pot tier per hand. The aggregate `pot` and
-- `winners`/`rake` columns on `poker_hand_log` stay populated for
-- back-compat with v1 readers.
--
-- `cap` is the per-seat commitment level this tier was peeled at
-- (every contributor put in `cap - prev_cap` to reach this tier).
-- `eligible` is the comma-separated discord IDs of seats that could
-- win this tier (those whose totalCommittedThisHand reached `cap`).
-- `winners` is the subset of `eligible` who actually won the tier
-- after hand evaluation. `payouts` is `discordId:amount,...` for
-- each winner's slice of the post-rake `amount`.
CREATE TABLE poker_hand_pot (
    id            BIGSERIAL    PRIMARY KEY,
    hand_log_id   BIGINT       NOT NULL REFERENCES poker_hand_log(id) ON DELETE CASCADE,
    tier_index    INT          NOT NULL CHECK (tier_index >= 0),
    cap           BIGINT       NOT NULL CHECK (cap >= 0),
    amount        BIGINT       NOT NULL CHECK (amount >= 0),
    eligible      VARCHAR(512) NOT NULL,
    winners       VARCHAR(512) NOT NULL,
    payouts       VARCHAR(512) NOT NULL,
    UNIQUE (hand_log_id, tier_index)
);
CREATE INDEX idx_poker_hand_pot_log ON poker_hand_pot (hand_log_id);
