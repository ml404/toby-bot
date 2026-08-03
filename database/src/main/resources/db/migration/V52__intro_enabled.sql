-- Per-intro on/off switch.
--
-- Lets a user park an intro in a slot without it being picked on join —
-- seasonal tracks, one that's wearing thin, or muting yourself for a while
-- without losing the upload and having to find it again.
--
-- Defaults to TRUE so every existing row keeps playing exactly as before.

ALTER TABLE public.music_files
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;
