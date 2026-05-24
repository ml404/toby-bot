package bot.toby.command

import core.command.Command.Companion.replyAndDelete
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping

/**
 * Early-return helpers used by ~40 slash commands that share the same
 * "extract a required thing or reply-and-abort" prelude. Replaces the
 * inline `event.X ?: run { ...replyAndDelete(...); return null }` ladder
 * with single-line guarded extraction.
 *
 * Two reply families: [replyAndDelete] (plain text) for moderation /
 * misc commands; game commands keep using `WagerCommandEmbeds.replyError`
 * for the consistent themed error embed.
 */

/** Reply with [message] and schedule the standard delete-after; return null. */
fun SlashCommandInteractionEvent.guildOrReply(message: String, deleteDelay: Int): Guild? {
    val guild = guild
    if (guild == null) {
        hook.replyAndDelete(message, deleteDelay)
        return null
    }
    return guild
}

fun SlashCommandInteractionEvent.optionOrReply(
    name: String,
    message: String,
    deleteDelay: Int,
): OptionMapping? {
    val option = getOption(name)
    if (option == null) {
        hook.replyAndDelete(message, deleteDelay)
        return null
    }
    return option
}
