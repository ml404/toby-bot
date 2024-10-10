package bot.toby.button

import bot.logging.DiscordLogger
import database.dto.UserDto

interface IButton {
    val name: String
    val description: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?)
}