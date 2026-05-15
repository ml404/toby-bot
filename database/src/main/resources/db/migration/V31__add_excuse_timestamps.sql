-- Add audit timestamps and a reliable author identity column.
--
-- The original `author` column stores the user's effective name at submission
-- time, which is fine for display but useless for ownership checks (it
-- changes when someone updates their nickname, and collides between people
-- with the same name). The new author_discord_id column lets the new self-
-- delete-own-pending rule resolve "is this requester the author?" without
-- string-matching display names.
--
-- Existing rows leave author_discord_id NULL — those excuses predate the
-- column and can only be moderated by superusers, which matches today's
-- behavior.

ALTER TABLE public.excuse
    ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS approved_at       TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS author_discord_id BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_excuse_guild_approved_created
    ON public.excuse (guild_id, approved, created_at DESC);
