-- Positive participation incentives for TICKET_WEIGHTED lotteries.
-- Three additive levers, all default-off:
--
--   1. Bulk bonus tickets (per-purchase): a single /lottery buy N call
--      grants extra free tickets when N hits configured thresholds.
--      Tracked on the ticket row in `bonus_tickets`. Splitting into
--      one-ticket buys earns nothing — that's the whole point.
--
--   2. Escalating weight multiplier (per-user-total): the more total
--      tickets a buyer accumulates across the lottery, the more each of
--      their tickets is worth at draw time. Read from config at draw
--      time — no schema cost.
--
--   3. Pool-growth milestones (guild-wide): when total ticket count
--      crosses configured thresholds, the lottery pool absorbs a slice
--      of the jackpot. `milestones_fired` is the highest threshold that
--      has already paid out so we never double-fire on the same lottery.

ALTER TABLE toby_coin_jackpot_lottery_ticket
    ADD COLUMN bonus_tickets BIGINT NOT NULL DEFAULT 0;

ALTER TABLE toby_coin_jackpot_lottery
    ADD COLUMN milestones_fired BIGINT NOT NULL DEFAULT 0;
