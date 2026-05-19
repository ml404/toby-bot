-- Live the same way the pool watermark does: persist a digest of the
-- active incentive tiers at announce/refresh time so the 5-minute
-- LotteryRefreshJob can detect mid-lottery config edits and re-render
-- the embed's "Active incentives" field. Without this column, the
-- refresh short-circuit only fires when poolAmount changes, leaving
-- the embed stale after a web-UI tier edit.
--
-- NULL until first announce. Cleared by clearAnnouncement alongside
-- announced_pool_amount.
ALTER TABLE toby_coin_jackpot_lottery
    ADD COLUMN announced_incentives_digest VARCHAR(64);
