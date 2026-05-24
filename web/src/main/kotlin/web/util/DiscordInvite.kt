package web.util

import net.dv8tion.jda.api.Permission

/**
 * Builds the Discord OAuth invite URL the homepage / moderation /
 * intro pages all link to.
 *
 * The bitmask is `permissions=8` (Administrator). Trade-off: the
 * install dialog shows the server owner a single "Administrator"
 * tick instead of an itemised list, and the bot's role can grant
 * itself any permission a future feature needs without re-inviting.
 * The cost is a coarse install dialog — owners who want a tighter
 * role can edit the bot's role after install (Discord still honours
 * channel-level overrides over Administrator's blanket grant where
 * the owner sets them).
 */
object DiscordInvite {

    /** Discord's permissions bitmask = Administrator only (raw value `8`). */
    val permissionsBitmask: Long = Permission.ADMINISTRATOR.rawValue

    /**
     * Build the OAuth invite URL for the given Discord application
     * client id. Includes the `bot` and `applications.commands`
     * scopes; the latter is required for slash-command registration.
     */
    fun urlFor(clientId: String): String =
        "https://discord.com/api/oauth2/authorize?" +
            "client_id=$clientId" +
            "&permissions=$permissionsBitmask" +
            "&scope=bot%20applications.commands"
}
