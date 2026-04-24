package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import core.command.Command.Companion.deleteAfter
import core.menu.Menu
import core.menu.MenuContext
import database.dto.MusicDto

/**
 * Shared preamble for the select-menu handlers that operate on a single
 * intro (DeleteIntroMenu, EditIntroMenu). Defers the interaction, resolves
 * the selected intro via IntroHelper, and surfaces the standard
 * "Unable to find" ephemeral on miss so each menu can focus on its own flow.
 *
 * Returns `null` if either nothing was selected (swallowed silently, as in
 * the original per-menu code) or the intro couldn't be fetched (user is
 * notified). Callers should `?: return` on null.
 */
internal fun Menu.resolveSelectedIntroOrElse(
    ctx: MenuContext,
    introHelper: IntroHelper,
    deleteDelay: Int
): MusicDto? {
    val event = ctx.event
    logger.setGuildAndMemberContext(ctx.guild, event.member)
    event.deferReply(true).queue()

    logger.info { "Getting the selectedIntroId" }
    val selectedIntroId = event.values.firstOrNull() ?: return null

    logger.info { "Fetching the musicDto selected ..." }
    val selectedIntro = introHelper.findIntroById(selectedIntroId)
    if (selectedIntro == null) {
        event.hook.sendMessage("Unable to find the selected intro.").setEphemeral(true)
            .queue { it?.deleteAfter(deleteDelay) }
    }
    return selectedIntro
}
