package core.button

import core.log.Loggable
import database.dto.UserDto

interface Button : Loggable {
    val name: String
    val description: String
    val defersReply: Boolean get() = true

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int)
}