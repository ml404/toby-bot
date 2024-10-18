package core.managers

import core.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface CommandManager {

    val commands: List<Command>
    val slashCommands: MutableList<CommandData?>
    val musicCommands: List<Command>
    val dndCommands: List<Command>
    val moderationCommands: List<Command>
    val miscCommands: List<Command>
    val fetchCommands: List<Command>

    fun getCommand(search: String): Command? = commands.find { it.name.equals(search, true) }

    fun handle(event: SlashCommandInteractionEvent)
}
