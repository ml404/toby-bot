-- Allow monster HP to be a dice expression like "3d20+30" so each spawned
-- instance can roll its own HP at initiative time. Existing fixed integers
-- round-trip through text (the same parser accepts a bare integer literal).
ALTER TABLE dnd_monster_template
    ALTER COLUMN max_hp TYPE VARCHAR(32) USING max_hp::text;

ALTER TABLE dnd_monster_template
    RENAME COLUMN max_hp TO hp_expression;
