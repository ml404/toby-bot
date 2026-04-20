-- Saved monster templates a DM can reuse when composing initiative. Scoped to the
-- DM's Discord id so a single library follows them across every guild / campaign
-- they run. HP and AC are optional so minimal templates are just (name, modifier).
CREATE TABLE IF NOT EXISTS dnd_monster_template (
    id BIGSERIAL PRIMARY KEY,
    dm_discord_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    initiative_modifier INT NOT NULL DEFAULT 0,
    max_hp INT,
    ac INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_monster_template_dm
    ON dnd_monster_template(dm_discord_id);
