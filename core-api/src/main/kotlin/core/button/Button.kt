package core.button

import core.log.Loggable
import core.managers.Named
import database.dto.user.UserDto

interface Button : Loggable, Named {
    override val name: String
    val description: String
    val defersReply: Boolean get() = true

    /**
     * Buttons that update their source message (board games, paginators)
     * should ack via `deferEdit()` instead of `deferReply(true)`. This
     * produces no "thinking" indicator and lets the handler use
     * `event.message.editMessage*()` / `event.hook.editOriginal*()`
     * without leaving a dangling deferred reply.
     *
     * When this is true, `defersReply` is ignored and the channel-typing
     * indicator is also suppressed.
     */
    val defersEdit: Boolean get() = false

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int)
}