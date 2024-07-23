package toby.button

import toby.jpa.dto.UserDto

interface IButton {
    val name: String
    val description: String
    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?)
}