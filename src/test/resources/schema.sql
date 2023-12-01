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
    file_name character varying(100),
    music_blob blob,
    id character varying(100) NOT NULL,
    file_vol integer
);

DROP TABLE IF EXISTS public."user";
CREATE TABLE public."user" (
    discord_id bigint NOT NULL,
    guild_id bigint NOT NULL,
    super_user boolean DEFAULT false NOT NULL,
    music_permission boolean DEFAULT true NOT NULL,
    dig_permission boolean DEFAULT true NOT NULL,
    meme_permission boolean DEFAULT true NOT NULL,
    music_file_id character varying(100) NOT NULL,
    social_credit bigint,
    initiative smallint default 0
);
