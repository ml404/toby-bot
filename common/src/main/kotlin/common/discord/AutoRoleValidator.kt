package common.discord

import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember

/**
 * Single source of truth for "can TobyBot auto-assign [role] to a
 * member?" Used by every entry point that touches the `auto_role`
 * table (slash command write, web write, listener-side assignment) so
 * the three places can't drift on which roles qualify or on the
 * user-facing error wording.
 *
 * Returns `null` when the role can be assigned; otherwise an admin-
 * facing reason string. Callers translate that into either a JSON
 * 4xx body, an ephemeral Discord reply, or a WARN log line.
 *
 * The three failure modes:
 *   - `@everyone` — never auto-assignable; everyone is already in it.
 *   - managed roles — owned by an integration (Twitch sub, booster,
 *     bot role, etc.); Discord refuses programmatic assignment.
 *   - role outranks the bot — JDA's [SelfMember.canInteract] reports
 *     false; the bot's runtime [Role.addRoleToMember] call would fail.
 */
object AutoRoleValidator {

    /**
     * @return null when [role] is assignable, otherwise a human-readable
     *         error message safe to surface to admins.
     */
    fun validate(role: Role, selfMember: SelfMember): String? = when {
        role.isPublicRole ->
            "Cannot auto-assign @everyone."
        role.isManaged ->
            "${role.name} is managed by an integration and can't be assigned by the bot."
        !selfMember.canInteract(role) ->
            "${role.name} sits above TobyBot's role — move TobyBot's role higher to allow assignment."
        else -> null
    }
}
