package bot.toby.command.commands.misc

import core.command.Command
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class HelpCommand @Autowired constructor(private val commands: List<Command>) : MiscCommand {
    companion object {
        private const val COMMAND = "command"
    }

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        // No argument → show the in-Discord command overview rather than
        // punting to an external wiki. This is the surface a brand-new user
        // hits first, so it answers "what can this bot do?" inline, hands off
        // a zero-setup first action, and carries a category drill-down menu.
        // Sent without auto-delete so the dropdown stays usable; it's
        // ephemeral, so there's no channel clutter to clean up.
        if (event.options.isEmpty()) {
            event.hook.sendMessageEmbeds(HelpOverview.embed(commands))
                .addComponents(ActionRow.of(HelpOverview.selectMenu(commands)))
                .queue()
            return
        }

        val searchOptional = event.getOption(COMMAND)?.asString
        val command = getCommand(searchOptional!!)
        if (command == null) {
            event.hook.replyEphemeralAndDelete("Nothing found for command '$searchOptional'", deleteDelay)
            return
        }
        event.hook.replyEphemeralEmbedAndDelete(HelpOverview.commandDetailEmbed(command), deleteDelay)
    }

    private fun getCommand(searchOptional: String) = commands.find { it.name.lowercase() == searchOptional }

    override val name: String
        get() = "help"
    override val description: String
        get() = "See everything the bot can do, or pass a command name for details on just that one."
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, COMMAND, "Command you would like help with", false, true))
}
