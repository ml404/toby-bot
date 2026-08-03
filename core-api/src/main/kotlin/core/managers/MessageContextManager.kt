package core.managers

import core.command.MessageContextCommand
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface MessageContextManager : NamedRegistry<MessageContextCommand> {

    val messageContextCommands: List<MessageContextCommand>
    override val items: List<MessageContextCommand> get() = messageContextCommands

    /** Registered alongside the slash commands in the startup bulk overwrite. */
    val contextCommandData: List<CommandData> get() = messageContextCommands.map { it.commandData }

    fun getMessageContextCommand(search: String): MessageContextCommand? = findByName(search)

    fun handle(event: MessageContextInteractionEvent)
}
