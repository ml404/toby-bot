package toby.button

import database.dto.UserDto
import logging.DiscordLogger

interface IButton {
    val name: String
    val description: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?)
}