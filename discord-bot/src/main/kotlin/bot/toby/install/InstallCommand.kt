package bot.toby.install

import bot.toby.command.commands.moderation.ModerationCommand
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

/**
 * `/install` — owner-only. Opens the in-Discord install wizard (welcome
 * embed + Express / Custom / Skip buttons). Always re-runnable; idempotency
 * for the auto-welcome on guild-join is enforced by the
 * [InstallWelcomeHandler] checking the `INSTALL_MODE` sentinel.
 */
@Component
class InstallCommand : ModerationCommand {

    override val name = "install"
    override val description = "Owner-only — open the bot's install / setup wizard."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (!InstallAuth.requireOwner(event)) return
        val guild = event.guild ?: run {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }
        event.replyEmbeds(InstallWizard.welcomeEmbed(guild.name))
            .addComponents(InstallWizard.wizardButtons())
            .queue()
    }
}
