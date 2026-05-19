-- Per-user web-push subscriptions. One row per (user, browser/device);
-- a single user can hold multiple subscriptions (laptop + phone + ...).
--
-- `endpoint` is the unique subscription URL the user's browser
-- generates when it calls `pushManager.subscribe(...)`. Per RFC 8030
-- §4.1 the endpoint is unique per subscription across the whole web,
-- so it's the natural PK. Indexing on `discord_id` keeps the
-- "send a push to this user" lookup constant-time.
--
-- `p256dh` is the client's base64url-encoded P-256 ECDH public key
-- the server uses to derive a shared secret for payload encryption.
-- `auth` is the client's 16-byte base64url-encoded auth secret used
-- as input to the HKDF. Both come back from the browser's
-- PushSubscription.toJSON().keys.
--
-- `user_agent` is purely cosmetic — surfaces "Firefox on Linux" in the
-- "Enabled devices" list on the preferences page so users can revoke
-- a specific device without having to guess which subscription is which.
CREATE TABLE push_subscription (
    endpoint     TEXT        NOT NULL PRIMARY KEY,
    discord_id   BIGINT      NOT NULL,
    p256dh       VARCHAR(255) NOT NULL,
    auth         VARCHAR(255) NOT NULL,
    user_agent   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

-- Hot lookup: every push event for a user fans out to all that user's
-- subscriptions, so we read by discord_id far more often than by
-- endpoint. Endpoint PK alone doesn't help.
CREATE INDEX idx_push_subscription_user ON push_subscription(discord_id);
