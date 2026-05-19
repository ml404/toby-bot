-- Per-user predefined TobyCoin price triggers. When the price tick
-- crosses a user's target threshold, the bot auto-executes the
-- declared trade (BUY/SELL of `amount` coins) and DMs a receipt via
-- the existing PRICE_ALERT notification kind.
--
-- Fire-direction is implicit: at /pricealert add time the slash
-- command captures the current market price into `price_at_creation`.
-- The fire condition is "newPrice has reached the target from the
-- side it was on at creation", i.e.
--   (price_at_creation - threshold_price) * (newPrice - threshold_price) <= 0
-- One-shot: on fire, the row gets `fired_at` stamped and `enabled`
-- flipped false so a price that oscillates around the threshold can't
-- re-fire the same trade. To re-arm, the user re-runs /pricealert add.
CREATE TABLE user_price_trigger (
    id                  BIGSERIAL PRIMARY KEY,
    discord_id          BIGINT        NOT NULL,
    guild_id            BIGINT        NOT NULL,
    threshold_price     NUMERIC(20,6) NOT NULL,
    price_at_creation   NUMERIC(20,6) NOT NULL,
    side                VARCHAR(8)    NOT NULL,
    amount              BIGINT        NOT NULL,
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    fired_at            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Hot path: every price tick scans this index per guild.
CREATE INDEX idx_user_price_trigger_guild_enabled
    ON user_price_trigger (guild_id) WHERE enabled;

-- /pricealert list lookup.
CREATE INDEX idx_user_price_trigger_user
    ON user_price_trigger (discord_id, guild_id);
