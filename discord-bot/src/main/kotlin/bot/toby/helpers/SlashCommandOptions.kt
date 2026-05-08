package bot.toby.helpers

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/**
 * Kotlin-idiomatic accessors for slash-command options. Replaces the
 * `Optional.ofNullable(event.getOption(NAME)).map { obj.asInt }.orElse(default)`
 * pattern — Java Optional reads as alien noise inside Kotlin code and
 * the `.orElse(default)` chain hides the default value.
 *
 *   val sides = event.intOption(DICE_NUMBER, 20)
 *   val reason = event.stringOption(REASON)
 *
 * Only the variants currently in use are exposed; add new typed
 * accessors (member/user/long/boolean) when a real call site appears.
 */

fun SlashCommandInteractionEvent.intOption(name: String, default: Int = 0): Int =
    getOption(name)?.asInt ?: default

fun SlashCommandInteractionEvent.stringOption(name: String): String? =
    getOption(name)?.asString
