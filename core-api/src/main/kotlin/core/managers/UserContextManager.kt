package core.managers

import core.command.UserContextCommand
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface UserContextManager : NamedRegistry<UserContextCommand> {

    val userContextCommands: List<UserContextCommand>
    override val items: List<UserContextCommand> get() = userContextCommands

    /** Registered alongside the slash commands in the startup bulk overwrite. */
    val contextCommandData: List<CommandData> get() = userContextCommands.map { it.commandData }

    fun getUserContextCommand(search: String): UserContextCommand? = findByName(search)

    fun handle(event: UserContextInteractionEvent)
}
