-- Leveling / engagement system. XP is awarded by the same engagement events
-- that already grant social credit (intro plays, voice sessions, commands,
-- messages), but tracked separately so spending credit at the casino does not
-- knock a user back down a level.

-- Lifetime XP per user per guild. Same primary-key shape as social_credit so
-- the leveling system is per-guild from day one.
ALTER TABLE public."user"
    ADD COLUMN xp BIGINT NOT NULL DEFAULT 0;

-- Daily-cap ledger for XP. Mirrors voice_credit_daily so the same anti-farm
-- pattern (clamp award to today's headroom, upsert the row) carries over.
CREATE TABLE xp_daily (
    discord_id BIGINT NOT NULL,
    guild_id   BIGINT NOT NULL,
    earn_date  DATE   NOT NULL,
    xp_earned  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, earn_date)
);

-- Per-guild role rewards. When a user crosses `level`, the configured Discord
-- role is assigned. Server owners manage these via the moderation web UI.
CREATE TABLE level_role_reward (
    guild_id BIGINT NOT NULL,
    level    INT    NOT NULL,
    role_id  BIGINT NOT NULL,
    PRIMARY KEY (guild_id, level)
);

-- Optional level gate for vanity titles. Default 0 means "no gate" so every
-- existing title stays purchasable without further migration.
ALTER TABLE title
    ADD COLUMN required_level INT NOT NULL DEFAULT 0;
