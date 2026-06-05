-- Per-user saved cube lists for the `/cube` web tool. A user pastes a card
-- list, names it, and it's stored against their Discord account so they can
-- reload it later from any device.
--
-- A name is unique per user, so the natural key is (discord_id, name); the
-- PK doubles as the "list this user's cubes" index since discord_id is its
-- leading column. Re-saving an existing name upserts (refreshes `cards` and
-- `updated_at`).
CREATE TABLE cube_list (
    discord_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    cards      TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (discord_id, name)
);
