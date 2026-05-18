-- Engagement loop: daily login streak, achievements, notification preferences.
-- All three slot into the existing per-guild model (every PK includes
-- guild_id so progress in one server doesn't leak into another) and mirror
-- the daily-cap ledger pattern from xp_daily / ubi_daily / tip_daily.

-- Per-(user, guild) streak ledger. One row tracks the live run and the
-- personal best. last_claim_date is NULL until the user's first claim.
-- The service computes "same day → AlreadyClaimed", "yesterday →
-- increment streak", "older or never → reset to 1".
CREATE TABLE login_streak (
    discord_id      BIGINT NOT NULL,
    guild_id        BIGINT NOT NULL,
    current_streak  INT    NOT NULL DEFAULT 0 CHECK (current_streak >= 0),
    longest_streak  INT    NOT NULL DEFAULT 0 CHECK (longest_streak >= 0),
    last_claim_date DATE,
    total_claims    BIGINT NOT NULL DEFAULT 0 CHECK (total_claims >= 0),
    PRIMARY KEY (discord_id, guild_id)
);

-- Achievement catalogue. Static-ish; seeded at startup from a Kotlin
-- registry so contributors don't write a migration per badge. `code` is
-- the stable referent used by the engine; `name`/`description`/`icon`
-- are display-only and safe to edit. xp_reward / credit_reward fire
-- once on unlock via XpAwardService / SocialCreditAwardService.
CREATE TABLE achievement (
    id            BIGSERIAL    PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(96)  NOT NULL,
    description   VARCHAR(255) NOT NULL,
    category      VARCHAR(32)  NOT NULL,
    icon          VARCHAR(32),
    xp_reward     INT          NOT NULL DEFAULT 0 CHECK (xp_reward >= 0),
    credit_reward BIGINT       NOT NULL DEFAULT 0 CHECK (credit_reward >= 0),
    threshold     BIGINT       NOT NULL DEFAULT 1 CHECK (threshold > 0),
    hidden        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_achievement_category ON achievement(category);

-- Append-once unlock log. (discord_id, guild_id, achievement_id) is
-- unique, so AchievementService.unlock can be called repeatedly without
-- double-awarding (insert-on-conflict-do-nothing).
CREATE TABLE user_achievement (
    discord_id     BIGINT      NOT NULL,
    guild_id       BIGINT      NOT NULL,
    achievement_id BIGINT      NOT NULL REFERENCES achievement(id),
    unlocked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (discord_id, guild_id, achievement_id)
);
CREATE INDEX idx_user_achievement_user ON user_achievement(discord_id, guild_id, unlocked_at DESC);

-- In-progress counter for accumulation-style achievements ("win 10
-- duels", "play 100 blackjack hands"). Single-event achievements
-- (threshold = 1) skip this table entirely.
CREATE TABLE achievement_progress (
    discord_id     BIGINT      NOT NULL,
    guild_id       BIGINT      NOT NULL,
    achievement_id BIGINT      NOT NULL REFERENCES achievement(id),
    progress       BIGINT      NOT NULL DEFAULT 0 CHECK (progress >= 0),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (discord_id, guild_id, achievement_id)
);

-- Per-user opt-in/out for each notification channel kind. Absence of a
-- row falls back to the per-kind default in NotificationChannelKind.
-- Per-guild because notification context (e.g. "you won the lottery in
-- guild X") is always guild-scoped.
CREATE TABLE user_notification_pref (
    discord_id   BIGINT      NOT NULL,
    guild_id     BIGINT      NOT NULL,
    channel_kind VARCHAR(32) NOT NULL,
    opt_in       BOOLEAN     NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (discord_id, guild_id, channel_kind)
);
