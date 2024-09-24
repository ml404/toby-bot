package toby.menu.menus

import toby.command.ICommand.Companion.deleteAfter
import toby.handler.EventWaiter
import toby.helpers.IntroHelper
import toby.logging.DiscordLogger
import toby.menu.IMenu
import toby.menu.MenuContext

class EditIntroMenu(
    private val introHelper: IntroHelper,
    private val eventWaiter: EventWaiter
) : IMenu {

    private lateinit var logger: DiscordLogger

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger = DiscordLogger.getLoggerForGuildId(ctx.guild.idLong)
        val event = ctx.selectEvent
        event.deferReply(true).queue()

        logger.info { "Getting the selectedIntroId" }
        val selectedIntroId = event.values.firstOrNull() ?: return

        // Fetch the selected MusicDto
        logger.info { "Fetching the musicDto selected ..." }

        val selectedIntro = introHelper.findIntroById(selectedIntroId)

        if (selectedIntro != null) {
            // Prompt the user to reply with a new volume
            logger.info { "Valid musicDto selected." }

            event.hook.sendMessage("You've selected ${selectedIntro.fileName}. Please reply with the new volume (0-100).")
                .setEphemeral(true).queue()

            // Wait for the user's next message
            eventWaiter.waitForMessage(
                condition = { msgEvent ->
                    msgEvent.author.idLong == event.user.idLong && msgEvent.channel.idLong == event.channel.idLong
                },
                action = { msgEvent ->
                    logger.info { "Waiting for a response from the user for the new volume" }

                    val newVolume = msgEvent.message.contentRaw.toIntOrNull()

                    if (newVolume != null && newVolume in 0..100) {
                        selectedIntro.introVolume = newVolume
                        introHelper.saveIntro(selectedIntro)

                        msgEvent.channel.sendMessage("Volume updated successfully to $newVolume!").queue{ it?.deleteAfter(deleteDelay) }
                        logger.info { "Volume updated successfully to $newVolume!" }

                        msgEvent.message.deleteAfter(0)
                    } else {
                        logger.warn { "Invalid volume was sent" }
                        msgEvent.channel.sendMessage("Invalid volume. Please enter a number between 0 and 100.").queue{ it?.deleteAfter(deleteDelay) }
                        msgEvent.message.deleteAfter(0)
                    }
                },
                timeout = 10000L,  // 10-second timeout
                timeoutAction = {
                    event.hook.sendMessage("No response received. Volume update canceled.").setEphemeral(true).queue{ it?.deleteAfter(deleteDelay) }
                }
            )
        } else {
            event.hook.sendMessage("Unable to find the selected intro.").setEphemeral(true).queue{ it?.deleteAfter(deleteDelay) }
        }
    }


    override val name: String
        get() = "editintro"
}
