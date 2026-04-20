-- Session log / event feed for an active D&D campaign. Append-only; events linked to a
-- campaign are deleted when the campaign is deleted. ref_event_id lets hit/miss
-- annotations point at the ROLL event they qualify.
CREATE TABLE IF NOT EXISTS dnd_campaign_event (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES dnd_campaign(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    actor_discord_id BIGINT,
    actor_name VARCHAR(100),
    ref_event_id BIGINT REFERENCES dnd_campaign_event(id) ON DELETE CASCADE,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dnd_campaign_event_campaign_created
    ON dnd_campaign_event(campaign_id, created_at);

CREATE INDEX IF NOT EXISTS idx_dnd_campaign_event_ref
    ON dnd_campaign_event(ref_event_id);
