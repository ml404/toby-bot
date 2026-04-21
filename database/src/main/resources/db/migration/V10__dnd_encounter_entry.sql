-- Roster rows on an encounter. Exactly one of (monster_template_id) or
-- (adhoc_name) is populated. For template entries, quantity expands into that
-- many initiative entries at roll time (e.g. 4 x Goblin). Ad-hoc entries are
-- single monsters defined inline (quantity is ignored).
--
-- monster_template_id is ON DELETE SET NULL so deleting a saved monster
-- doesn't wipe a pre-built encounter; the web layer renders the orphan row
-- with a "(missing template)" label and keeps the delete action available.
--
-- sort_order drives drag-and-drop reordering via the reorder endpoint.
CREATE TABLE IF NOT EXISTS dnd_encounter_entry (
    id BIGSERIAL PRIMARY KEY,
    encounter_id BIGINT NOT NULL
        REFERENCES dnd_encounter(id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    monster_template_id BIGINT
        REFERENCES dnd_monster_template(id) ON DELETE SET NULL,
    quantity INT NOT NULL DEFAULT 1,
    adhoc_name VARCHAR(100),
    adhoc_initiative_modifier INT NOT NULL DEFAULT 0,
    adhoc_hp_expression VARCHAR(32),
    adhoc_ac INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_encounter_entry_encounter
    ON dnd_encounter_entry(encounter_id, sort_order);
