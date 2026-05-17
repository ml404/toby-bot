-- Persistence for saved music playlists surfaced via the web music dashboard.
--
-- music_playlist stores the playlist header; music_playlist_item holds the
-- ordered tracks. We store a small denormalised snapshot of each track's
-- title/author/duration/source so the UI can render the playlist without
-- re-resolving every identifier against Lavaplayer. The `identifier` column
-- holds whatever Lavaplayer needs to re-load the track (URL, ytsearch term,
-- spsearch term, etc.) — we resolve it via the existing loadAndPlay path on
-- load.

CREATE TABLE IF NOT EXISTS public.music_playlist (
    id                BIGSERIAL                PRIMARY KEY,
    guild_id          BIGINT                   NOT NULL,
    owner_discord_id  BIGINT                   NOT NULL,
    name              VARCHAR(80)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_music_playlist_owner_name
    ON public.music_playlist (guild_id, owner_discord_id, lower(name));

CREATE INDEX IF NOT EXISTS idx_music_playlist_guild
    ON public.music_playlist (guild_id);

CREATE TABLE IF NOT EXISTS public.music_playlist_item (
    id           BIGSERIAL                PRIMARY KEY,
    playlist_id  BIGINT                   NOT NULL REFERENCES public.music_playlist(id) ON DELETE CASCADE,
    position     INTEGER                  NOT NULL,
    identifier   TEXT                     NOT NULL,
    title        TEXT,
    author       TEXT,
    duration_ms  BIGINT,
    source_name  VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS idx_music_playlist_item_playlist_position
    ON public.music_playlist_item (playlist_id, position);
