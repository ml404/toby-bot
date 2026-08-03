package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.EDIT_INTRO
import bot.toby.modal.modals.EditIntroModal
import core.menu.Menu
import core.menu.MenuContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Second half of `/editintro`: the user has picked an intro from the select
 * menu, so open [EditIntroModal] pre-filled with its current values.
 *
 * Deliberately does *not* defer — a modal must be the first response to a
 * component interaction. `DefaultMenuManager` leaves ephemeral menu messages
 * un-acked (it only disables components on non-ephemeral ones) and
 * `/editintro` always sends its menu ephemerally, so the interaction is still
 * open when we get here.
 */
@Component
class EditIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper,
) : Menu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, event.member)

        val selectedIntroId = event.values.firstOrNull() ?: return
        val selectedIntro = introHelper.findIntroById(selectedIntroId)
        if (selectedIntro == null) {
            logger.info { "No intro found for id '$selectedIntroId'" }
            event.reply("Unable to find the selected intro.").setEphemeral(true).queue()
            return
        }

        logger.info { "Opening intro edit modal for '$selectedIntroId'" }
        event.replyModal(EditIntroModal.buildFor(selectedIntro)).queue()
    }

    override val name: String get() = EDIT_INTRO
}
