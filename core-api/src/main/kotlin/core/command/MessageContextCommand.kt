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

    /**
     * Whether the manager should acknowledge the interaction before dispatch.
     *
     * Deferring is right for anything that replies with a message, and wrong
     * for anything that opens a modal: a modal has to be the *first* response
     * to an interaction, so a deferred command can never show one. Commands
     * that call `replyModal` set this false and take on the 3-second ack
     * budget themselves.
     */
    val defersReply: Boolean get() = true

    val commandData: CommandData get() = Commands.message(name)

    fun handle(event: MessageContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int)
}
