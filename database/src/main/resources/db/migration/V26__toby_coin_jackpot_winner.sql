-- Jackpot winner cooldown tracking. One row per (guild, user) with the
-- timestamp of their most recent jackpot win.
--
-- JackpotService.isOnCooldown compares now() - last_won_at against the
-- per-guild JACKPOT_WINNER_COOLDOWN_DAYS config to block repeat winners
-- within the configured window. Applies to both casino-roll wins (the
-- existing rollOnWin path in JackpotHelper) and lottery draw wins
-- (drawLottery in JackpotLotteryService) so the same player can't sweep
-- both within the cooldown.
--
-- Default cooldown is 0 days (gate disabled, current behaviour); rows
-- still get written so admins can later flip the cooldown on without
-- having to backfill history.

CREATE TABLE toby_coin_jackpot_winner (
    guild_id        BIGINT                   NOT NULL,
    discord_id      BIGINT                   NOT NULL,
    last_won_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_won_amount BIGINT                   NOT NULL CHECK (last_won_amount >= 0),
    PRIMARY KEY (guild_id, discord_id)
);
