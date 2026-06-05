-- Public, shareable snapshots of a cube card list. Anyone with the link can
-- open it in the web tool (`/cube/c/<token>`); only a logged-in user can
-- create one, and the creator's id is kept for moderation/cleanup.
--
-- `token` is a short random URL-safe string used both as the PK and as the
-- path segment in the share link. A snapshot is immutable — re-sharing an
-- edited cube mints a new token rather than mutating an existing row.
CREATE TABLE shared_cube (
    token      VARCHAR(24)  NOT NULL PRIMARY KEY,
    discord_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    cards      TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
