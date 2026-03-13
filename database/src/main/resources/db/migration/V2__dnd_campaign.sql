-- D&D campaign and player tracking tables

CREATE TABLE IF NOT EXISTS public.dnd_campaign (
    id            BIGSERIAL    PRIMARY KEY,
    guild_id      BIGINT       NOT NULL,
    channel_id    BIGINT       NOT NULL,
    dm_discord_id BIGINT       NOT NULL,
    name          VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    state         TEXT
);

CREATE TABLE IF NOT EXISTS public.dnd_campaign_player (
    campaign_id       BIGINT  NOT NULL REFERENCES public.dnd_campaign (id),
    player_discord_id BIGINT  NOT NULL,
    guild_id          BIGINT  NOT NULL,
    character_id      BIGINT,
    alive             BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (campaign_id, player_discord_id)
);
