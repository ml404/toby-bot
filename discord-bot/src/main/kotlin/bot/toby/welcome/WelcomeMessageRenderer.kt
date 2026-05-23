package bot.toby.welcome

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User

/**
 * Substitutes the four supported placeholders in welcome / goodbye
 * messages, returning the rendered text. Kept as a pure object (no JDA
 * action side effects) so the placeholder grammar is unit-testable
 * without mocking message-send infrastructure.
 *
 * Supported placeholders:
 *   `{user}`        — user mention (`<@123>`); pings the user.
 *   `{user.name}`   — display name (effective name when the user is in
 *                     the guild as a [net.dv8tion.jda.api.entities.Member],
 *                     otherwise the raw user name). Goodbye messages get
 *                     the raw name fallback because the member has just
 *                     left.
 *   `{server}`      — guild display name.
 *   `{membercount}` — non-bot member count snapshot. Computed at render
 *                     time so the value matches what admins see in Discord.
 *
 * Unknown braces are passed through verbatim — a typo like `{username}`
 * appears as-is in the announcement, which is a clearer signal to the
 * admin than silent removal.
 */
object WelcomeMessageRenderer {

    const val DEFAULT_WELCOME = "Welcome to {server}, {user}! You're our {membercount}-th member 🎉"
    const val DEFAULT_GOODBYE = "{user.name} has left {server}. We're now down to {membercount}."

    /**
     * Renders [template] (or [default] when [template] is blank/null) by
     * substituting the four supported placeholders against the live JDA
     * context. The welcome / goodbye distinction is collapsed into the
     * caller's choice of [default] so the renderer doesn't need to know
     * which surface it's serving — see [AnnouncementKind] for the bundle
     * of (config keys, default template, embed accent) per surface.
     */
    fun render(
        template: String?,
        default: String,
        guild: Guild,
        user: User,
        memberDisplayName: String? = null,
    ): String {
        val effective = template?.takeIf { it.isNotBlank() } ?: default
        val displayName = memberDisplayName?.takeIf { it.isNotBlank() } ?: user.name
        val memberCount = guild.members.count { !it.user.isBot }
        return effective
            .replace("{user.name}", displayName)
            .replace("{user}", user.asMention)
            .replace("{server}", guild.name)
            .replace("{membercount}", memberCount.toString())
    }
}
