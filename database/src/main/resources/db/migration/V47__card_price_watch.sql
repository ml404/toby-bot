-- Per-user Magic card price watches. A user asks to be alerted when a
-- card's market price (Scryfall, in their chosen currency) crosses a
-- threshold in a direction (BELOW / ABOVE). A scheduled job batch-fetches
-- prices for every watched card and DMs the user via the CARD_PRICE_ALERT
-- notification kind when a watch's condition is met.
--
-- One-shot: on fire, `fired_at` is stamped and `enabled` flipped false so a
-- price oscillating around the threshold can't re-alert. The user re-arms by
-- adding the watch again. `guild_id` records where the watch was created so
-- the notification router can resolve the user's per-guild DM opt-in (0 for
-- watches created on the web, which has no guild context — those route via
-- the CARD_PRICE_ALERT default opt-in).
CREATE TABLE card_price_watch (
    id              BIGSERIAL     PRIMARY KEY,
    discord_id      BIGINT        NOT NULL,
    guild_id        BIGINT        NOT NULL DEFAULT 0,
    card_name       VARCHAR(255)  NOT NULL,
    currency        VARCHAR(8)    NOT NULL,
    direction       VARCHAR(8)    NOT NULL,
    threshold       NUMERIC(20,6) NOT NULL,
    price_at_creation NUMERIC(20,6),
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    fired_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Job hot path: scan every enabled watch (prices are global, one fetch
-- pass covers all guilds/users).
CREATE INDEX idx_card_price_watch_enabled
    ON card_price_watch (enabled) WHERE enabled;

-- "list my watches" lookup.
CREATE INDEX idx_card_price_watch_user
    ON card_price_watch (discord_id);
