package core.managers

import core.command.Command
import core.command.CommandContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface CommandManager : NamedRegistry<Command> {

    val commands: List<Command>
    override val items: List<Command> get() = commands
    val slashCommands: MutableList<CommandData?>
    val musicCommands: List<Command>
    val dndCommands: List<Command>
    val moderationCommands: List<Command>
    val miscCommands: List<Command>
    val fetchCommands: List<Command>
    val economyCommands: List<Command>

    val lastCommands: Map<Guild, Pair<Command, CommandContext>>

    fun getCommand(search: String): Command? = findByName(search)

    fun handle(event: SlashCommandInteractionEvent)
}
