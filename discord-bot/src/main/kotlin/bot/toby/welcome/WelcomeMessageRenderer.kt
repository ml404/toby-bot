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

    fun render(
        template: String?,
        guild: Guild,
        user: User,
        memberDisplayName: String? = null,
    ): String {
        val effective = template?.takeIf { it.isNotBlank() } ?: DEFAULT_WELCOME
        return substitute(effective, guild, user, memberDisplayName)
    }

    fun renderGoodbye(
        template: String?,
        guild: Guild,
        user: User,
        memberDisplayName: String? = null,
    ): String {
        val effective = template?.takeIf { it.isNotBlank() } ?: DEFAULT_GOODBYE
        return substitute(effective, guild, user, memberDisplayName)
    }

    private fun substitute(
        template: String,
        guild: Guild,
        user: User,
        memberDisplayName: String?,
    ): String {
        val displayName = memberDisplayName?.takeIf { it.isNotBlank() } ?: user.name
        val memberCount = guild.members.count { !it.user.isBot }
        return template
            .replace("{user.name}", displayName)
            .replace("{user}", user.asMention)
            .replace("{server}", guild.name)
            .replace("{membercount}", memberCount.toString())
    }
}
