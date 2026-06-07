package bot.toby.install.button

import bot.toby.command.commands.misc.HelpOverview
import bot.toby.install.InstallWizard
import core.button.Button
import core.button.ButtonContext
import core.command.Command
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * "✨ What can I do?" — the one welcome-message button that is NOT
 * owner-gated. Anyone can click it to get the same categorized `/help`
 * overview, ephemerally, so a curious member has an immediate zero-setup
 * action instead of waiting on the owner to finish the install wizard.
 *
 * Uses the default [Button.defersReply] (ephemeral) ack from
 * [bot.toby.managers.DefaultButtonManager], so the handler just sends the
 * overview embed as a follow-up.
 */
@Component
class InstallHelpButton @Autowired constructor(
    private val commands: List<Command>,
) : Button {

    override val name: String = InstallWizard.BTN_HELP
    override val description: String = "Show what the bot can do — usable by anyone, not just the owner."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        ctx.event.hook.sendMessageEmbeds(HelpOverview.embed(commands))
            .addComponents(ActionRow.of(HelpOverview.selectMenu(commands)))
            .queue()
    }
}
