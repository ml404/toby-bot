package core.modal

import common.logging.DiscordLogger

interface Modal {
    val name: String
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)

    fun handle(ctx: ModalContext, deleteDelay: Int)
}
