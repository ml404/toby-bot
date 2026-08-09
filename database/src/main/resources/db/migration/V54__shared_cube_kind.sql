-- A shared snapshot can now be either a cube (a card list, opened at
-- `/magic/c/<token>`) or a deal (a dealt set of packs, opened at
-- `/magic/d/<token>`). Both are immutable snapshots of text addressed by the
-- same kind of token, so they share this table rather than duplicating it.
--
-- Existing rows are all cubes, which the default covers.
ALTER TABLE shared_cube
    ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'CUBE';
