DROP TABLE IF EXISTS public.brothers;
CREATE TABLE public.brothers (
    discord_id bigint,
    brother_name character varying(50) NOT NULL
);

DROP TABLE IF EXISTS public.config;
CREATE TABLE public.config (
    name character varying(50) NOT NULL,
    "value" character varying(100) NOT NULL,
    guild_id character varying DEFAULT 'all'::character varying NOT NULL
);

DROP TABLE IF EXISTS public.excuse;
CREATE TABLE public.excuse (
    guild_id bigint NOT NULL,
    author character varying NOT NULL,
    excuse character varying(200) NOT NULL,
    approved boolean NOT NULL,
    id bigint AUTO_INCREMENT PRIMARY KEY
);


DROP TABLE IF EXISTS public.music_files;
CREATE TABLE public.music_files (
    id VARCHAR(255) PRIMARY KEY NOT NULL,
    file_name VARCHAR,
    file_vol INT,
    discord_id BIGINT,
    guild_id BIGINT,
    index INT,
    music_blob BYTEA,
    music_blob_hash VARCHAR(64)
);

DROP TABLE IF EXISTS public."user";
CREATE TABLE public."user" (
    discord_id bigint NOT NULL,
    guild_id bigint NOT NULL,
    super_user boolean DEFAULT false NOT NULL,
    music_permission boolean DEFAULT true NOT NULL,
    dig_permission boolean DEFAULT true NOT NULL,
    meme_permission boolean DEFAULT true NOT NULL,
    social_credit bigint,
    initiative smallint default 0
);
