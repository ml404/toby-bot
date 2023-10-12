DROP TABLE BROTHERS IF EXISTS;
CREATE TABLE BROTHERS (
    "DISCORD_ID" bigint,
    "BROTHER_NAME" character varying(50) NOT NULL
);


DROP TABLE CONFIG IF EXISTS;
CREATE TABLE CONFIG (
    "NAME" character varying(50) NOT NULL,
    "VALUE" character varying(100) NOT NULL,
    "GUILD_ID" character varying DEFAULT 'all'::character varying NOT NULL
);

DROP TABLE EXCUSE IF EXISTS;
CREATE TABLE EXCUSE (
    "GUILD_ID" bigint NOT NULL,
    "AUTHOR" character varying NOT NULL,
    "EXCUSE" character varying(200) NOT NULL,
    "APPROVED" boolean NOT NULL,
    "ID" integer NOT NULL
);

DROP TABLE MUSIC_FILES IF EXISTS;
CREATE TABLE MUSIC_FILES (
    "FILE_NAME" character varying(100),
    "MUSIC_BLOB" character varying(200),
    "ID" character varying(100) NOT NULL,
    "FILE_VOL" integer
);

DROP TABLE "USER" IF EXISTS;
CREATE TABLE "USER" (
    "DISCORD_ID" bigint NOT NULL,
    "GUILD_ID" bigint NOT NULL,
    "SUPER_USER" boolean DEFAULT false NOT NULL,
    "MUSIC_PERMISSION" boolean DEFAULT true NOT NULL,
    "DIG_PERMISSION" boolean DEFAULT true NOT NULL,
    "MEME_PERMISSION" boolean DEFAULT true NOT NULL,
    "MUSIC_FILE_ID" character varying(100) NOT NULL,
    "SOCIAL_CREDIT" bigint
);