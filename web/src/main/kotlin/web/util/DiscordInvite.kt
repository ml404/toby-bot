package web.util

import net.dv8tion.jda.api.Permission

/**
 * Builds the Discord OAuth invite URL the homepage / moderation /
 * intro pages all link to. Centralised so the permissions bitmask is
 * computed in one place — adding a new bot feature that needs a
 * Discord permission is a single edit to [REQUIRED_PERMISSIONS]
 * instead of three string literals scattered across controllers.
 *
 * The bitmask is granular rather than `permissions=8` (Administrator).
 * Trade-off: the install dialog shows the server owner an itemised
 * list of what each permission is for, and a later "tighten the
 * bot's role" pass keeps the bot working — the owner can see in role
 * settings what each permission grants and is less likely to drop one
 * the UX needs (e.g. MANAGE_ROLES, which is required to set channel-
 * level permission overrides for read-only / admin-only mod-log
 * channels). The cost is a more specific install dialog and a
 * maintenance commitment to keep this list current.
 */
object DiscordInvite {

    /**
     * Permissions the bot's UX needs in every server it joins. Each
     * is justified by a feature that exercises it; adding a new
     * permission here implies a new feature that needs it.
     */
    private val REQUIRED_PERMISSIONS: Set<Permission> = setOf(
        // ---- Baseline messaging ----
        Permission.VIEW_CHANNEL,
        Permission.MESSAGE_SEND,
        Permission.MESSAGE_HISTORY,
        Permission.MESSAGE_EMBED_LINKS,
        Permission.MESSAGE_ATTACH_FILES,
        Permission.MESSAGE_EXT_EMOJI,
        Permission.MESSAGE_ADD_REACTION,           // emoji polls

        // ---- Moderation ----
        Permission.KICK_MEMBERS,                   // /moderation kick
        Permission.MESSAGE_MANAGE,                 // configurable delete-delay
        Permission.MANAGE_CHANNEL,                 // create read-only / admin-only mod-log channels
        Permission.MANAGE_ROLES,                   // (a) set channel-level permission overrides
                                                   // when creating those channels, and
                                                   // (b) assign the role minted by an
                                                   // equipped vanity title.

        // ---- Voice ----
        Permission.VOICE_CONNECT,
        Permission.VOICE_SPEAK,
        Permission.VOICE_USE_VAD,
        Permission.VOICE_MUTE_OTHERS,              // /moderation voice mute
        Permission.VOICE_MOVE_OTHERS,              // /moderation voice move
    )

    /** Discord's permissions bitmask for [REQUIRED_PERMISSIONS]. */
    val permissionsBitmask: Long = Permission.getRaw(REQUIRED_PERMISSIONS)

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
