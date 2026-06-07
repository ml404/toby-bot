package bot.toby.menu.menus

import bot.toby.command.commands.misc.HelpOverview
import common.logging.DiscordLogger
import core.command.Command
import core.menu.Menu
import core.menu.MenuContext
import net.dv8tion.jda.api.components.actionrow.ActionRow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Drill-down handler for the `/help` overview's category select menu. When
 * a reader picks a category (or "Full overview"), it edits the ephemeral
 * help message in place to show that category's commands with their
 * descriptions, re-arming the same menu so they can keep browsing.
 *
 * The help overview is always ephemeral (the `/help` reply and the install
 * "What can I do?" button are both ephemeral), so this acks via `deferEdit`
 * and edits through the interaction hook — [bot.toby.managers.DefaultMenuManager]
 * skips its source-message edit for ephemeral messages, which can't be
 * edited via the bot webhook.
 */
@Component
class HelpCategoryMenu @Autowired constructor(
    private val commands: List<Command>,
) : Menu {

    override val name: String = HelpOverview.MENU_ID

    private val log: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        val event = ctx.event
        val selected = event.selectedOptions.firstOrNull()?.value ?: HelpOverview.OVERVIEW_VALUE
        log.info { "Help category drill-down: '$selected'" }
        event.deferEdit().queue {
            event.hook.editOriginalEmbeds(HelpOverview.categoryEmbed(selected, commands))
                .setComponents(ActionRow.of(HelpOverview.selectMenu(commands)))
                .queue()
        }
    }
}
