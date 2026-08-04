package core.command

import core.log.Loggable
import core.managers.Named
import database.dto.user.UserDto
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands

/**
 * A right-click → **Apps** entry on a *member*.
 *
 * The twin of [MessageContextCommand], and separate for the same reason it is
 * separate from [Command]: Discord delivers these as
 * `UserContextInteractionEvent` and registers them under a display [name]
 * (spaces and capitals allowed, 32 characters) rather than a slash-command
 * name.
 *
 * Discord allows five of these per application, so they are worth spending
 * carefully — an entry that opens onto its own buttons goes further than five
 * entries that each do one thing.
 */
interface UserContextCommand : Loggable, Named {
    override val name: String

    /** Whether the manager should defer with an ephemeral ack before dispatch. */
    val ephemeral: Boolean get() = true

    /**
     * Whether the manager should acknowledge the interaction before dispatch.
     *
     * Deferring is right for anything that replies with a message, and wrong
     * for anything that opens a modal: a modal has to be the *first* response
     * to an interaction, so a deferred command can never show one.
     */
    val defersReply: Boolean get() = true

    val commandData: CommandData get() = Commands.user(name)

    fun handle(event: UserContextInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int)
}
