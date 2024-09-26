package toby.button

import toby.jpa.dto.UserDto
import toby.logging.DiscordLogger

interface IButton {
    val name: String
    val description: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?)
}