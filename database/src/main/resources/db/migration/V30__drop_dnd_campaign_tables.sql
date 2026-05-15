-- D&D campaign features removed in favour of the official DnD Beyond
-- Discord bot. Lookups (/dnd command) and the generic /roll command stay.
DROP TABLE IF EXISTS dnd_encounter_entry CASCADE;
DROP TABLE IF EXISTS dnd_encounter CASCADE;
DROP TABLE IF EXISTS dnd_monster_attack CASCADE;
DROP TABLE IF EXISTS dnd_monster_template CASCADE;
DROP TABLE IF EXISTS dnd_campaign_event CASCADE;
DROP TABLE IF EXISTS dnd_campaign_session_note CASCADE;
DROP TABLE IF EXISTS dnd_campaign_player CASCADE;
DROP TABLE IF EXISTS dnd_campaign CASCADE;
DROP TABLE IF EXISTS dnd_character_sheet CASCADE;
ALTER TABLE "user" DROP COLUMN IF EXISTS dnd_beyond_character_id;
