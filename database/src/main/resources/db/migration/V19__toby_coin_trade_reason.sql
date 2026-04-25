-- Per-trade attribution for the chart marker tooltip.
--
-- Until now every trade row looked the same on the chart whether the
-- trader hit `/economy sell` themselves, the Titles "Buy with TOBY"
-- button auto-sold to top up a credit shortfall, or the casino sold to
-- fund a wager. Adding a discriminator lets the chart label say WHY
-- the trade happened — "(title top-up)", "(casino top-up)" — so a
-- viewer can tell organic activity from automated tops-up.
--
-- Done in one ALTER so the DEFAULT lands together with the NOT NULL
-- constraint (Postgres back-fills existing rows with the default in
-- the same statement). Pre-migration trades get the catch-all 'USER'
-- since we can't know retroactively what tagged them.

ALTER TABLE toby_coin_trade
    ADD COLUMN reason VARCHAR(32) NOT NULL DEFAULT 'USER';
