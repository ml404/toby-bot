package bot.toby.menu

import bot.logging.DiscordLogger

interface IMenu {
    fun handle(ctx: MenuContext, deleteDelay: Int)
    val name: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)
}