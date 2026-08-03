package core.command

import core.log.Loggable
import core.managers.Named
import database.dto.user.UserDto
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands

/**
 * A right-click → **Apps** entry on a message.
 *
 * Distinct from [Command] because Discord delivers these as
 * `MessageContextInteractionEvent`, not `SlashCommandInteractionEvent`, and
 * registers them with a display [name] (spaces and capitals allowed, 32 chars)
 * rather than a slash-command name.
 */
interface MessageContextCommand : Loggable, Named {
    override val name: String

    /** Whether the manager should defer with an ephemeral ack before dispatch. */
    val ephemeral: Boolean get() = true

    val commandData: CommandData get() = Commands.message(name)

    fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int)
}
