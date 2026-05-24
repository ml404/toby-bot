package core.button

import core.log.Loggable
import core.managers.Named
import database.dto.user.UserDto

interface Button : Loggable, Named {
    override val name: String
    val description: String
    val defersReply: Boolean get() = true

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int)
}