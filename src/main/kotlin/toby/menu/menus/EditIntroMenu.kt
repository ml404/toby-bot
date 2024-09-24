package toby.menu.menus

import toby.handler.EventWaiter
import toby.helpers.IntroHelper
import toby.menu.IMenu
import toby.menu.MenuContext

class EditIntroMenu(
    private val introHelper: IntroHelper,
    private val eventWaiter: EventWaiter
) : IMenu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.selectEvent
        val selectedIntroId = event.values.firstOrNull() ?: return

        // Fetch the selected MusicDto
        val selectedIntro = introHelper.findIntroById(selectedIntroId)

        if (selectedIntro != null) {
            // Prompt the user to reply with a new volume
            event.hook.sendMessage("You've selected ${selectedIntro.fileName}. Please reply with the new volume (0-100).")
                .setEphemeral(true).queue()

            // Wait for the user's next message
            eventWaiter.waitForMessage(
                condition = { msgEvent ->
                    msgEvent.author.idLong == event.user.idLong && msgEvent.channel.idLong == event.channel.idLong
                },
                action = { msgEvent ->
                    val newVolume = msgEvent.message.contentRaw.toIntOrNull()

                    if (newVolume != null && newVolume in 0..100) {
                        selectedIntro.introVolume = newVolume
                        introHelper.saveIntro(selectedIntro)

                        msgEvent.channel.sendMessage("Volume updated successfully to $newVolume!").queue()
                    } else {
                        msgEvent.channel.sendMessage("Invalid volume. Please enter a number between 0 and 100.").queue()
                    }
                },
                timeout = 30_000L,  // 30-second timeout
                timeoutAction = {
                    event.hook.sendMessage("No response received. Volume update canceled.").queue()
                }
            )
        } else {
            event.hook.sendMessage("Unable to find the selected intro.").setEphemeral(true).queue()
        }
    }


    override val name: String
        get() = "editintro"
}
