-- Low-engagement lottery improvements, aimed at guilds where only a
-- handful of players (2-3) buy in each day:
--
--   1. Rollover pot: when a daily draw cancels (below the min-buyer
--      threshold / no tickets) or a NUMBER_MATCH draw pays out nothing,
--      the undistributed pool can be parked on the closed lottery row
--      (`rollover_out`) instead of returning to the jackpot. The next
--      open for the same (guild, mode) claims it into the fresh prize
--      pool, so quiet days visibly grow tomorrow's pot instead of
--      reading as duds. Opt-in via LOTTERY_DAILY_ROLLOVER_ENABLED.
--
--   2. Participation streaks: one row per (guild, user) tracking
--      consecutive calendar days (UTC) with at least one lottery ticket
--      bought. When the streak reaches the configured threshold
--      (LOTTERY_STREAK_DAYS), each further participation day pays a
--      bonus (LOTTERY_STREAK_BONUS) drained from the jackpot pool.

ALTER TABLE toby_coin_jackpot_lottery
    ADD COLUMN rollover_out BIGINT NOT NULL DEFAULT 0;

CREATE TABLE toby_coin_lottery_streak
(
    guild_id                BIGINT NOT NULL,
    discord_id              BIGINT NOT NULL,
    streak_days             INT    NOT NULL DEFAULT 0,
    last_participation_date DATE,
    PRIMARY KEY (guild_id, discord_id)
);
