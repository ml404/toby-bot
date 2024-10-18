package core.menu

import common.logging.DiscordLogger

interface Menu {
    fun handle(ctx: MenuContext, deleteDelay: Int)
    val name: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)
}
