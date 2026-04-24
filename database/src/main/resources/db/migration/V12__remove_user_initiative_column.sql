-- Initiative is now derived from the linked D&D Beyond character sheet's
-- DEX modifier rather than stored on the user. Drop the legacy column.

ALTER TABLE public."user"
    DROP COLUMN IF EXISTS initiative;
