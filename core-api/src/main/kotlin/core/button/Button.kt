package core.button

import common.logging.DiscordLogger
import database.dto.UserDto

interface Button {
    val name: String
    val description: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?)
}