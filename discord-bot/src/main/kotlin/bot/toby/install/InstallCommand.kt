package bot.toby.install

import bot.toby.command.commands.moderation.ModerationCommand
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.economy.JackpotService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `/install` — owner-only, two subcommands:
 *
 *  - `/install setup` opens the in-Discord install wizard (welcome embed +
 *    Express / Custom / Skip buttons). Always re-runnable; idempotency for
 *    the auto-welcome on guild-join is enforced by [InstallWelcomeHandler].
 *  - `/install summary` shows the current per-guild configuration snapshot
 *    plus recommended next steps (see [InstallSummary]).
 *
 * Responds directly (the wizard posts publicly; the summary is ephemeral),
 * so it opts out of the manager's auto-defer with `defersReply = false`,
 * matching the other direct-reply moderation command (`/setconfig`).
 */
@Component
class InstallCommand(
    private val configService: ConfigService,
    private val jackpotService: JackpotService,
    @param:Value($$"${app.base-url:}") private val webBaseUrl: String = "",
) : ModerationCommand {

    override val name = "install"
    override val description = "Owner-only — set up the bot or review your current setup."

    override val defersReply: Boolean = false

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_SETUP, "Open the install / setup wizard."),
        SubcommandData(SUB_SUMMARY, "Show your current setup and recommended next steps."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (!InstallAuth.requireOwner(event)) return
        val guild = event.guild ?: run {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }

        when (event.subcommandName) {
            SUB_SUMMARY -> {
                val reader = InstallWizard.configReader(configService, guild.id)
                event.replyEmbeds(
                    InstallSummary.embed(
                        guild = guild,
                        reader = reader,
                        jackpotPool = jackpotService.getPool(guild.idLong),
                        winChanceDisplay = jackpotService.winProbabilityDisplay(guild.idLong),
                        webBaseUrl = webBaseUrl,
                    ),
                ).setEphemeral(true).queue()
            }
            // SUB_SETUP (and any unexpected/null subcommand) opens the wizard.
            else -> {
                event.replyEmbeds(InstallWizard.welcomeEmbed(guild.name, event.jda.guildCache.size().toInt()))
                    .addComponents(InstallWizard.wizardButtons())
                    .queue()
            }
        }
    }

    companion object {
        const val SUB_SETUP = "setup"
        const val SUB_SUMMARY = "summary"
    }
}
