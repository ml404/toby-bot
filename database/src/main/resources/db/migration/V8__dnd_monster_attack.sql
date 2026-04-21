-- Per-monster attacks. On its turn the DM picks one of these and a target;
-- the bot rolls 1d20+to_hit_modifier vs target AC, and on hit rolls the
-- damage_expression and applies it to the target. Cascade-deletes with the
-- parent template so deleting a monster from the library cleans up its kit.
CREATE TABLE IF NOT EXISTS dnd_monster_attack (
    id BIGSERIAL PRIMARY KEY,
    monster_template_id BIGINT NOT NULL
        REFERENCES dnd_monster_template(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    to_hit_modifier INT NOT NULL DEFAULT 0,
    damage_expression VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_monster_attack_template
    ON dnd_monster_attack(monster_template_id);
