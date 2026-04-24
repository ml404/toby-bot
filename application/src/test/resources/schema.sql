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
    music_blob_hash VARCHAR(64),
    clip_start_ms INT,
    clip_end_ms INT
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
    initiative smallint default 0,
    dnd_beyond_character_id bigint default null,
    active_title_id bigint default null,
    activity_tracking_opt_out boolean DEFAULT false NOT NULL
);

DROP TABLE IF EXISTS public.voice_session;
CREATE TABLE public.voice_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    left_at TIMESTAMP WITH TIME ZONE,
    counted_seconds BIGINT,
    credits_awarded BIGINT NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS public.voice_credit_daily;
CREATE TABLE public.voice_credit_daily (
    discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    earn_date DATE NOT NULL,
    credits BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, earn_date)
);

DROP TABLE IF EXISTS public.monthly_credit_snapshot;
CREATE TABLE public.monthly_credit_snapshot (
    discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    snapshot_date DATE NOT NULL,
    social_credit BIGINT NOT NULL,
    PRIMARY KEY (discord_id, guild_id, snapshot_date)
);

DROP TABLE IF EXISTS public.user_owned_title;
DROP TABLE IF EXISTS public.guild_title_role;
DROP TABLE IF EXISTS public.title;
CREATE TABLE public.title (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    label TEXT NOT NULL UNIQUE,
    cost BIGINT NOT NULL,
    description TEXT,
    color_hex TEXT,
    hoisted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE public.user_owned_title (
    discord_id BIGINT NOT NULL,
    title_id BIGINT NOT NULL REFERENCES public.title(id),
    bought_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (discord_id, title_id)
);

CREATE TABLE public.guild_title_role (
    guild_id BIGINT NOT NULL,
    title_id BIGINT NOT NULL REFERENCES public.title(id),
    discord_role_id BIGINT NOT NULL,
    PRIMARY KEY (guild_id, title_id)
);

DROP TABLE IF EXISTS public.activity_session;
CREATE TABLE public.activity_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    activity_name TEXT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE
);

DROP TABLE IF EXISTS public.activity_monthly_rollup;
CREATE TABLE public.activity_monthly_rollup (
    discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    month_start DATE NOT NULL,
    activity_name TEXT NOT NULL,
    seconds BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (discord_id, guild_id, month_start, activity_name)
);

DROP TABLE IF EXISTS public.dnd_campaign_player;
DROP TABLE IF EXISTS public.dnd_campaign;
CREATE TABLE public.dnd_campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    dm_discord_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    state TEXT
);

CREATE TABLE public.dnd_campaign_player (
    campaign_id BIGINT NOT NULL REFERENCES public.dnd_campaign(id),
    player_discord_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    character_id BIGINT,
    alive BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (campaign_id, player_discord_id)
);

DROP TABLE IF EXISTS public.dnd_character_sheet;
CREATE TABLE public.dnd_character_sheet (
    character_id BIGINT PRIMARY KEY,
    sheet_json TEXT NOT NULL,
    last_updated TIMESTAMP NOT NULL DEFAULT NOW()
);
