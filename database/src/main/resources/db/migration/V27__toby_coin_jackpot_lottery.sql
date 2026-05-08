-- Per-guild jackpot lottery events.
--
-- Admins fire `/jackpotadmin lottery_open` to drain a runaway jackpot
-- pool through a ticketed multi-winner draw rather than letting one
-- casino-roll winner sweep the whole thing. The opening admin chooses
-- ticket price, duration, winner count, and what fraction of the
-- current pool seeds the prize. Players then `/lottery buy` tickets
-- (credits go into the lottery's prize pool, not the jackpot row), and
-- the admin closes via `/jackpotadmin lottery_draw` (split prize across
-- weighted winners) or `lottery_cancel` (refund tickets, return seed
-- pool to the jackpot).
--
-- A lottery is OPEN until the admin runs draw or cancel; closes_at is
-- informational/UI only — closes are admin-driven, not auto. Only one
-- OPEN lottery may exist per guild at a time, enforced by a partial
-- unique index.

CREATE TABLE toby_coin_jackpot_lottery (
    id            BIGSERIAL PRIMARY KEY,
    guild_id      BIGINT                   NOT NULL,
    ticket_price  BIGINT                   NOT NULL CHECK (ticket_price > 0),
    pool_amount   BIGINT                   NOT NULL CHECK (pool_amount >= 0),
    winner_count  INT                      NOT NULL CHECK (winner_count >= 1),
    opened_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    closes_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    status        VARCHAR(16)              NOT NULL CHECK (status IN ('OPEN','DRAWN','CANCELLED'))
);

-- Only one OPEN row per guild — partial unique index avoids two admins
-- racing to open simultaneously and produces a clean unique-violation
-- the service can translate to a friendly "lottery already open" error.
CREATE UNIQUE INDEX toby_coin_jackpot_lottery_one_open_per_guild
    ON toby_coin_jackpot_lottery (guild_id) WHERE status = 'OPEN';

CREATE TABLE toby_coin_jackpot_lottery_ticket (
    lottery_id   BIGINT NOT NULL REFERENCES toby_coin_jackpot_lottery(id) ON DELETE CASCADE,
    discord_id   BIGINT NOT NULL,
    ticket_count INT    NOT NULL CHECK (ticket_count > 0),
    spent        BIGINT NOT NULL CHECK (spent >= 0),
    PRIMARY KEY (lottery_id, discord_id)
);
