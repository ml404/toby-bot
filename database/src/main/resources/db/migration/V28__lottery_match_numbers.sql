-- Match-numbers (Pick 5 of 1-49) lottery variant + daily-draw
-- idempotency table.
--
-- The existing `toby_coin_jackpot_lottery` table held only one mode of
-- lottery: TICKET_WEIGHTED, where each ticket was a weight in the draw
-- and top-K winners shared the prize. PR #405 shipped this for
-- admin-fired one-shot drain events. We now add a second mode,
-- NUMBER_MATCH, that runs DAILY: players pick 5 numbers from 1-49,
-- the job draws 5 winning numbers at midnight UTC, and prize tiers
-- pay by match count (5/4/3/2 matches → 60/25/10/5 % of pool).
--
-- Both modes coexist: one OPEN row is allowed per (guild, mode), so
-- an admin-fired weighted lottery can run alongside the daily
-- match-numbers draw without clashing.

-- 1. Distinguish the two lottery modes on existing rows. Existing rows
--    are TICKET_WEIGHTED by default so back-compat is automatic.
ALTER TABLE toby_coin_jackpot_lottery
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'TICKET_WEIGHTED'
        CHECK (mode IN ('TICKET_WEIGHTED', 'NUMBER_MATCH'));

-- 2. Match-numbers picks/draw. Fixed at 5/49 in the MVP — columns are
--    here so a future variant can vary without another migration.
ALTER TABLE toby_coin_jackpot_lottery
    ADD COLUMN pick_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN number_max    INT NOT NULL DEFAULT 0,
    ADD COLUMN drawn_numbers VARCHAR(64);  -- comma-separated, NULL until DRAWN

-- 3. Per-ticket picks. NULL for TICKET_WEIGHTED tickets.
ALTER TABLE toby_coin_jackpot_lottery_ticket
    ADD COLUMN picked_numbers VARCHAR(64);

-- 4. Replace the one-OPEN-per-guild index with one-OPEN-per-(guild, mode)
--    so the daily match-numbers row and an admin-fired weighted row
--    can both be OPEN simultaneously without violating uniqueness.
DROP INDEX IF EXISTS toby_coin_jackpot_lottery_one_open_per_guild;

CREATE UNIQUE INDEX toby_coin_jackpot_lottery_one_open_per_guild_mode
    ON toby_coin_jackpot_lottery (guild_id, mode) WHERE status = 'OPEN';

-- 5. Idempotency for the daily auto-draw (LotteryDailyJob).
--    Mirrors UbiDailyDto's pattern (V24__ubi_daily.sql): one row per
--    (guild, draw_date) so a restart mid-cron-tick can't double-draw.
CREATE TABLE toby_coin_jackpot_lottery_daily (
    guild_id  BIGINT NOT NULL,
    draw_date DATE   NOT NULL,
    PRIMARY KEY (guild_id, draw_date)
);
