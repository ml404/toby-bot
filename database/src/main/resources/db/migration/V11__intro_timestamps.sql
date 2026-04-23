-- Start/end timestamps (milliseconds) for clipping intro playback.
-- Both optional: null means play from 0 / to end of track.
-- Column names are prefixed with clip_ so they don't collide with the SQL
-- reserved words END/START (which trip up H2 in PostgreSQL mode).

ALTER TABLE public.music_files
    ADD COLUMN IF NOT EXISTS clip_start_ms INT,
    ADD COLUMN IF NOT EXISTS clip_end_ms   INT;
