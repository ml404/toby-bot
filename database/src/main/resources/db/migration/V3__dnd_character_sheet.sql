-- D&D character sheet cache table.
-- Stores Gson-serialised CharacterSheet JSON fetched from D&D Beyond,
-- used as a fallback when the D&D Beyond API is throttled or unavailable.

CREATE TABLE IF NOT EXISTS public.dnd_character_sheet (
    character_id BIGINT    PRIMARY KEY,
    sheet_json   TEXT      NOT NULL,
    last_updated TIMESTAMP NOT NULL DEFAULT NOW()
);
