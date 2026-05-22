package bot.toby.install.button

import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto

/**
 * Shared base for every install-wizard button. Centralizes:
 *
 * - The owner-gate (ephemeral reject if `event.member?.isOwner != true`),
 *   which all install buttons need.
 * - `defersReply = false` — install buttons edit the source message via
 *   `deferEdit + hook.editOriginal*`, not a separate ephemeral reply.
 *
 * Subclasses override [handleAsOwner] and optionally [ownerErrorMessage]
 * if a more specific reject string is wanted.
 */
abstract class OwnerOnlyInstallButton : Button {

    override val defersReply: Boolean = false

    /** Per-button error string. Defaults to a generic owner-only message. */
    protected open fun ownerErrorMessage(): String = DEFAULT_OWNER_ERROR

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.member?.isOwner != true) {
            event.reply(ownerErrorMessage()).setEphemeral(true).queue()
            return
        }
        handleAsOwner(ctx, requestingUserDto, deleteDelay)
    }

    /**
     * Called only when the owner gate has passed. Implementations must
     * provide an interaction response (typically `event.deferEdit().queue()`
     * followed by `event.hook.editOriginal*`), or an ephemeral reply for
     * secondary validation failures.
     */
    protected abstract fun handleAsOwner(
        ctx: ButtonContext,
        requestingUserDto: UserDto,
        deleteDelay: Int,
    )

    companion object {
        const val DEFAULT_OWNER_ERROR: String = "Only the server owner can use the install wizard."
    }
}
