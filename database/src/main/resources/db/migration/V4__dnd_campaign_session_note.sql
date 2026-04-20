-- Session notes attached to an active D&D campaign. Players and the DM can add;
-- author can delete their own, DM can delete any (enforced in the service layer).
CREATE TABLE IF NOT EXISTS dnd_campaign_session_note (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES dnd_campaign(id) ON DELETE CASCADE,
    author_discord_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_campaign_session_note_campaign_id
    ON dnd_campaign_session_note(campaign_id);
