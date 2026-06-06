package bot.toby.command.commands.mtg

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Shared plumbing for the Magic command family ([CubeCommand], [CardCommand],
 * [DeckCommand], [MtgReferenceCommand], [PriceWatchCommand]). Holds the
 * IO dispatcher and the two patterns every command repeats: launching an
 * IO-bound subcommand off the gateway thread (mirroring `/dnd`) and replying
 * with a self-deleting embed.
 */
abstract class AbstractMtgCommand(
    // Tests pass Dispatchers.Unconfined so launched work resolves synchronously.
    protected val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MtgCommand {

    /**
     * Runs an IO-bound subcommand on [dispatcher] so a blocking Scryfall fetch
     * or DB read never ties up the gateway. An unexpected failure resolves the
     * deferred reply with an error embed rather than leaving Discord's
     * "thinking…" spinner hanging.
     */
    protected fun launchHandling(ctx: CommandContext, block: suspend () -> Unit) {
        CoroutineScope(dispatcher).launch {
            runCatching { block() }.onFailure { e ->
                logger.error("MTG subcommand '${ctx.event.subcommandName}' failed: $e")
                ctx.event.hook.sendMessageEmbeds(
                    CubeEmbeds.errorEmbed("Something went wrong. Try again in a moment.")
                ).queue({}, {})
            }
        }
    }

    protected fun reply(ctx: CommandContext, embed: MessageEmbed, deleteDelay: Int) {
        ctx.event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
