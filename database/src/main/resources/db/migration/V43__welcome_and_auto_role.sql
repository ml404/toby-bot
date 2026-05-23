-- Welcome / goodbye announcements and auto-role-on-join.
--
-- Welcome and goodbye are configured through six existing-shape ConfigDto
-- keys so they ride the established /setconfig modal + moderation config-row
-- + bidirectional template guard infrastructure. The six keys are:
--   WELCOME_ENABLED, WELCOME_CHANNEL, WELCOME_MESSAGE,
--   GOODBYE_ENABLED, GOODBYE_CHANNEL, GOODBYE_MESSAGE
--
-- Realistic welcome / goodbye templates with placeholders ("Welcome to
-- {server}, {user}! You're our {membercount}-th member …") routinely run
-- over the historical config.value cap of VARCHAR(100). Widen the column
-- to TEXT so admins aren't forced to abbreviate. Existing rows (all
-- VARCHAR(100)-sized) migrate without truncation.
ALTER TABLE config
    ALTER COLUMN value TYPE TEXT;

-- Roles auto-assigned to every joining member. Composite (guild_id, role_id)
-- PK lets a guild bind any number of roles without an array column.
-- Conceptually parallels level_role_reward (added in V34): per-guild rows
-- of role bindings, applied by an event listener.
CREATE TABLE auto_role (
    guild_id BIGINT NOT NULL,
    role_id  BIGINT NOT NULL,
    PRIMARY KEY (guild_id, role_id)
);
