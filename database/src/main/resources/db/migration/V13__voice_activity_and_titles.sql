-- Voice activity tracking, monthly snapshots, and vanity titles.

-- One row per user-in-channel session. Closed rows carry counted_seconds
-- (only time when >=1 other non-bot human was present) and the credits
-- that were awarded on close.
CREATE TABLE voice_session (
    id              BIGSERIAL PRIMARY KEY,
    discord_id      BIGINT      NOT NULL,
    guild_id        BIGINT      NOT NULL,
    channel_id      BIGINT      NOT NULL,
    joined_at       TIMESTAMPTZ NOT NULL,
    left_at         TIMESTAMPTZ,
    counted_seconds BIGINT,
    credits_awarded BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_voice_session_user_guild_time
    ON voice_session (guild_id, discord_id, joined_at);
CREATE INDEX idx_voice_session_open
    ON voice_session (discord_id, guild_id)
    WHERE left_at IS NULL;

-- Daily cap ledger. Prevents AFK farming beyond a daily ceiling.
CREATE TABLE voice_credit_daily (
    discord_id BIGINT NOT NULL,
    guild_id   BIGINT NOT NULL,
    earn_date  DATE   NOT NULL,
    credits    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, earn_date)
);

-- Snapshot of social_credit at the start of each calendar month, per user
-- per guild. The monthly leaderboard job derives deltas from this.
CREATE TABLE monthly_credit_snapshot (
    discord_id    BIGINT      NOT NULL,
    guild_id      BIGINT      NOT NULL,
    snapshot_date DATE        NOT NULL,
    social_credit BIGINT      NOT NULL,
    PRIMARY KEY (discord_id, guild_id, snapshot_date)
);

-- Vanity titles catalog.
CREATE TABLE title (
    id          BIGSERIAL PRIMARY KEY,
    label       TEXT    NOT NULL UNIQUE,
    cost        BIGINT  NOT NULL,
    description TEXT,
    color_hex   TEXT,
    hoisted     BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO title (label, cost, description, color_hex, hoisted) VALUES
    ('⭐ Comrade',             100,  'Loyal citizen.',                                    '#F1C40F', FALSE),
    ('🎙️ Glorious Speaker',   500,  'Heard in voice channels far and wide.',             '#3498DB', FALSE),
    ('👑 Party Chair',         2000, 'A pillar of the server.',                           '#E67E22', TRUE),
    ('🥉 Bronze Citizen',      250,  'Dependable and present.',                           '#CD7F32', FALSE),
    ('🥈 Silver Citizen',      750,  'A recognised regular.',                             '#C0C0C0', FALSE),
    ('🥇 Gold Citizen',        2500, 'Among the server''s finest.',                       '#FFD700', TRUE),
    ('🤖 Toby''s Favourite',   5000, 'Hand-picked by the bot itself.',                    '#9B59B6', TRUE),
    ('💸 Big Spender',         1500, 'Knows how to move credits.',                        '#2ECC71', FALSE),
    ('🎵 DJ',                  800,  'Always queueing the bangers.',                      '#1ABC9C', FALSE),
    ('🌙 Night Owl',           600,  'Logs the late-night voice hours.',                  '#34495E', FALSE);

-- Ownership join table. A user can own many titles across their guild memberships.
CREATE TABLE user_owned_title (
    discord_id BIGINT      NOT NULL,
    title_id   BIGINT      NOT NULL REFERENCES title(id),
    bought_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (discord_id, title_id)
);

-- Maps (guild, title) to the bot-created Discord role. Populated lazily
-- the first time a user in a guild equips a title. Lets us reuse roles
-- across restarts and across buyers.
CREATE TABLE guild_title_role (
    guild_id        BIGINT NOT NULL,
    title_id        BIGINT NOT NULL REFERENCES title(id),
    discord_role_id BIGINT NOT NULL,
    PRIMARY KEY (guild_id, title_id)
);

-- Currently-equipped title per user per guild.
ALTER TABLE public."user"
    ADD COLUMN active_title_id BIGINT REFERENCES title(id);
