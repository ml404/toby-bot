-- Base schema: tables existing in production before migration tracking was introduced

CREATE TABLE IF NOT EXISTS public.brothers (
    discord_id   BIGINT               PRIMARY KEY,
    brother_name CHARACTER VARYING(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.config (
    name     CHARACTER VARYING(50)  NOT NULL,
    value    CHARACTER VARYING(100) NOT NULL,
    guild_id CHARACTER VARYING      NOT NULL DEFAULT 'all',
    PRIMARY KEY (name, guild_id)
);

CREATE TABLE IF NOT EXISTS public.excuse (
    id        BIGSERIAL PRIMARY KEY,
    guild_id  BIGINT                NOT NULL,
    author    CHARACTER VARYING     NOT NULL,
    excuse    CHARACTER VARYING(200) NOT NULL,
    approved  BOOLEAN               NOT NULL
);

CREATE TABLE IF NOT EXISTS public.music_files (
    id              CHARACTER VARYING(255) PRIMARY KEY NOT NULL,
    file_name       CHARACTER VARYING,
    file_vol        INT,
    discord_id      BIGINT,
    guild_id        BIGINT,
    index           INT,
    music_blob      BYTEA,
    music_blob_hash CHARACTER VARYING(64)
);

CREATE TABLE IF NOT EXISTS public."user" (
    discord_id              BIGINT   NOT NULL,
    guild_id                BIGINT   NOT NULL,
    super_user              BOOLEAN  NOT NULL DEFAULT FALSE,
    music_permission        BOOLEAN  NOT NULL DEFAULT TRUE,
    dig_permission          BOOLEAN  NOT NULL DEFAULT TRUE,
    meme_permission         BOOLEAN  NOT NULL DEFAULT TRUE,
    social_credit           BIGINT,
    initiative              SMALLINT          DEFAULT 0,
    dnd_beyond_character_id BIGINT            DEFAULT NULL,
    PRIMARY KEY (discord_id, guild_id)
);
