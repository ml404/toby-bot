package bot.toby.button

import bot.logging.DiscordLogger

interface IButton {
    val name: String
    val description: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ButtonContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?)
}