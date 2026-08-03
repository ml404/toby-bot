-- Intro health, play stats and measured loudness.
--
-- Intros play through the voice-join path, which has no interaction to reply
-- to, so a track that failed to load produced silence and a log line and
-- nothing else: the owner was never told, and both /listintros and the web
-- page kept listing a dead link as healthy. failure_count/last_failure_* give
-- that failure somewhere to live so it can be surfaced and acted on; a
-- successful load resets the counter, so it always reads as "consecutive
-- failures".
--
-- play_count/last_played_at answer "is this feature used, and by whom" —
-- previously unanswerable, since nothing recorded that an intro ever played.
--
-- measured_rms is the intro's own level, sampled by the playback filter the
-- first time it plays, and used to correct the volume on later plays so two
-- intros set to the same number sound equally loud.
ALTER TABLE public.music_files
    ADD COLUMN IF NOT EXISTS play_count          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_played_at      TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS failure_count       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_failure_at     TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS last_failure_reason VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS measured_rms        DOUBLE PRECISION NULL;

-- The guild intro report (/introstats) aggregates every row for one guild.
-- music_files is keyed by (discord_id, guild_id) with no index on guild_id
-- alone, so without this the report is a sequential scan over every intro in
-- every server — including their audio blobs.
CREATE INDEX IF NOT EXISTS idx_music_files_guild_id
    ON public.music_files (guild_id);
