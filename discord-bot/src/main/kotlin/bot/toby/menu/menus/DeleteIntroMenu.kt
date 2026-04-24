package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.DELETE_INTRO
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
        val selectedIntro = resolveSelectedIntroOrElse(ctx, introHelper, deleteDelay) ?: return
        introHelper.deleteIntro(selectedIntro)
        ctx.event.hook.sendMessage("Successfully deleted intro '${selectedIntro.fileName}'").setEphemeral(true)
            .queue { it?.deleteAfter(deleteDelay) }
    }


    override val name: String get() = DELETE_INTRO
}
