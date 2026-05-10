-- Track the Discord announcement message for an open lottery so a
-- refresh job can edit the embed when the prize pool grows after
-- ticket purchases.
--
-- Three nullable columns on `toby_coin_jackpot_lottery`:
--   - announcement_channel_id : where we posted the announce embed
--   - announcement_message_id : Discord message id we can edit later
--   - announced_pool_amount   : last pool value pushed to that embed
--                               (so refresh short-circuits when the
--                               pool hasn't changed since the last edit)
--
-- All three are nullable. Legacy rows (status=DRAWN/CANCELLED) and the
-- moments before the first announce all keep them NULL — refresh is
-- skipped when announcement_message_id is null. If a moderator deletes
-- the announcement, the refresh job clears these back to NULL so we
-- stop hammering Discord on every tick.
ALTER TABLE public.toby_coin_jackpot_lottery
    ADD COLUMN announcement_channel_id BIGINT NULL,
    ADD COLUMN announcement_message_id BIGINT NULL,
    ADD COLUMN announced_pool_amount   BIGINT NULL;
