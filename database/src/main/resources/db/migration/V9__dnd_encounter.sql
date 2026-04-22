-- Saved encounters a DM can set up in advance and load into the initiative
-- composer (or roll immediately). Scoped to the DM's Discord id, identical in
-- shape to dnd_monster_template, so one library follows them across guilds.
CREATE TABLE IF NOT EXISTS dnd_encounter (
    id BIGSERIAL PRIMARY KEY,
    dm_discord_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_encounter_dm
    ON dnd_encounter(dm_discord_id);
