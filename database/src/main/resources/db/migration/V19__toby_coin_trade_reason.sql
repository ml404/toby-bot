-- Per-trade attribution for the chart marker tooltip.
--
-- Until now every trade row looked the same on the chart whether the
-- trader hit `/economy sell` themselves, the Titles "Buy with TOBY"
-- button auto-sold to top up a credit shortfall, or the casino sold to
-- fund a wager. Adding a discriminator lets the chart label say WHY
-- the trade happened — "(title top-up)", "(casino top-up)" — so a
-- viewer can tell organic activity from automated tops-up.
--
-- Backfill is per-row; pre-existing rows can't tell you their source
-- after the fact, so they get the catch-all 'USER'. New writes are
-- explicit.

ALTER TABLE toby_coin_trade ADD COLUMN reason VARCHAR(32);
UPDATE toby_coin_trade SET reason = 'USER' WHERE reason IS NULL;
ALTER TABLE toby_coin_trade ALTER COLUMN reason SET NOT NULL;
ALTER TABLE toby_coin_trade ALTER COLUMN reason SET DEFAULT 'USER';
