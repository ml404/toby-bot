-- Persistence for the /team UI overhaul.
--
-- team_preset holds saved rosters per guild so users don't have to
-- re-type the same group every Friday. Members are stored as a CSV of
-- Discord snowflakes in TEXT; no other table in this repo uses
-- bigint[], and a side-table would require cascading-delete plumbing
-- we don't need today.
--
-- team_split_session is short-lived state for the preview-then-confirm
-- flow. The /team split modal writes a row, embeds the UUID in
-- Confirm/Reroll/Cancel button ids, and the buttons load the row back.
-- Rerolling updates assignments in place so the same buttons keep
-- working. There is no FK to team_preset — sessions outlive a preset
-- rename/delete and shouldn't break when one happens.

CREATE TABLE IF NOT EXISTS public.team_preset (
    id                    BIGSERIAL                PRIMARY KEY,
    guild_id              BIGINT                   NOT NULL,
    name                  VARCHAR(64)              NOT NULL,
    member_ids            TEXT                     NOT NULL,
    created_by_discord_id BIGINT                   NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_team_preset_guild_name
    ON public.team_preset (guild_id, lower(name));

CREATE INDEX IF NOT EXISTS idx_team_preset_guild
    ON public.team_preset (guild_id);

CREATE TABLE IF NOT EXISTS public.team_split_session (
    id                   UUID                     PRIMARY KEY,
    guild_id             BIGINT                   NOT NULL,
    requester_discord_id BIGINT                   NOT NULL,
    member_ids           TEXT                     NOT NULL,
    team_count           INTEGER                  NOT NULL,
    assignments          TEXT                     NOT NULL,
    team_names           TEXT                     NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_action          VARCHAR(16)              NOT NULL DEFAULT 'created'
);

CREATE INDEX IF NOT EXISTS idx_team_split_session_guild_created
    ON public.team_split_session (guild_id, created_at DESC);
