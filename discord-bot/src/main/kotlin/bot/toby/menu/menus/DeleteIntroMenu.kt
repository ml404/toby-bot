package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.EDIT_INTRO
import core.command.Command.Companion.deleteAfter
import core.menu.Menu
import core.menu.MenuContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class DeleteIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper
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
            introHelper.deleteIntro(selectedIntro)
            event.hook.sendMessage("Successfully deleted intro '${selectedIntro.fileName}'").setEphemeral(true)
                .queue { it?.deleteAfter(deleteDelay) }

        } else {
            event.hook.sendMessage("Unable to find the selected intro.").setEphemeral(true)
                .queue { it?.deleteAfter(deleteDelay) }
        }
    }


    override val name: String get() = EDIT_INTRO
}
