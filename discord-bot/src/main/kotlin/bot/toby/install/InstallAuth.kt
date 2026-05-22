package bot.toby.install

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback

/**
 * Owner-gate helper shared by every install-wizard entry point (buttons,
 * menus, slash command). Replies ephemerally with [message] and returns
 * `false` when the caller isn't the guild owner; otherwise returns
 * `true`. Callers branch on the result.
 *
 * Centralizing the check here means the wizard's permission policy
 * (currently "guild owner only, may change in future") has exactly one
 * implementation.
 */
object InstallAuth {

    const val DEFAULT_MESSAGE: String = "Only the server owner can use the install wizard."

    /** Override used by Express + Finish buttons (the "writes the install sentinel" flow). */
    const val SETUP_MESSAGE: String = "Only the server owner can run install setup."

    /** Override used by Skip. */
    const val DISMISS_MESSAGE: String = "Only the server owner can dismiss the install prompt."

    /** Override used by Back. */
    const val NAVIGATE_MESSAGE: String = "Only the server owner can navigate the install wizard."

    /** Override used by Toggle. */
    const val TOGGLE_MESSAGE: String = "Only the server owner can toggle install settings."

    fun requireOwner(event: IReplyCallback, message: String = DEFAULT_MESSAGE): Boolean {
        if (event.member?.isOwner == true) return true
        event.reply(message).setEphemeral(true).queue()
        return false
    }
}
