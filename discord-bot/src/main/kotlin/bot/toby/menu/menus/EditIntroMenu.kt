package bot.toby.menu.menus

import bot.toby.handler.EventWaiter
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.EDIT_INTRO
import core.command.Command.Companion.deleteAfter
import core.menu.Menu
import core.menu.MenuContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

@Component
class EditIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper,
    private val eventWaiter: EventWaiter
) : Menu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, event.member)
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
                .setEphemeral(true)
                .queue { messageHook ->
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
                                introHelper.updateIntro(selectedIntro)

                                messageHook.editMessage("Volume updated successfully to $newVolume!")
                                    .queue { it?.deleteAfter(deleteDelay) }

                                logger.info { "Volume updated successfully to $newVolume!" }
                                msgEvent.message.deleteAfter(0)
                            } else {
                                logger.warn { "Invalid volume was sent" }

                                messageHook.editMessage("Invalid volume. Please enter a number between 0 and 100.")
                                    .queue { it?.deleteAfter(deleteDelay) }

                                msgEvent.message.deleteAfter(0)
                            }
                        },
                        timeout = 10.seconds,
                        timeoutAction = {
                            messageHook
                                .editMessage("No response received. Volume update canceled.")
                                .queue { it?.deleteAfter(deleteDelay) }
                        }
                    )
                }
        } else {
            event.hook.sendMessage("Unable to find the selected intro.").setEphemeral(true)
                .queue { it?.deleteAfter(deleteDelay) }
        }
    }


    override val name: String get() = EDIT_INTRO
}
