package bot.toby.install.button

import bot.toby.install.InstallAuth
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto

/**
 * Shared base for every install-wizard button. Centralizes:
 *
 * - The owner-gate (ephemeral reject if `event.member?.isOwner != true`),
 *   shared with the menu via [InstallAuth.requireOwner].
 * - `defersReply = false` — install buttons edit the source message via
 *   `deferEdit + hook.editOriginal*`, not a separate ephemeral reply.
 *
 * Subclasses override [handleAsOwner] and optionally [ownerErrorMessage]
 * if a more specific reject string is wanted.
 */
abstract class OwnerOnlyInstallButton : Button {

    override val defersReply: Boolean = false

    /** Per-button error string. Defaults to the wizard's generic owner-only message. */
    protected open fun ownerErrorMessage(): String = InstallAuth.DEFAULT_MESSAGE

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        if (!InstallAuth.requireOwner(ctx.event, ownerErrorMessage())) return
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
}
