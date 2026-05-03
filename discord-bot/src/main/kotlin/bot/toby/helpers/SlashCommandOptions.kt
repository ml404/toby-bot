package bot.toby.helpers

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/**
 * Kotlin-idiomatic accessors for slash-command options. Replaces the
 * `Optional.ofNullable(event.getOption(NAME)).map { obj.asInt }.orElse(default)`
 * pattern that appears 25+ times across `bot/toby/command/commands/` —
 * Java Optional reads as alien noise inside Kotlin code, and the
 * .orElse(default) chain hides the default value.
 *
 *   val sides = event.intOption(DICE_NUMBER, 20)
 *   val target = event.memberOption(USER_NAME)
 *   val reason = event.stringOption(REASON)
 */

fun SlashCommandInteractionEvent.intOption(name: String, default: Int = 0): Int =
    getOption(name)?.asInt ?: default

fun SlashCommandInteractionEvent.longOption(name: String, default: Long = 0L): Long =
    getOption(name)?.asLong ?: default

fun SlashCommandInteractionEvent.stringOption(name: String): String? =
    getOption(name)?.asString

fun SlashCommandInteractionEvent.stringOption(name: String, default: String): String =
    getOption(name)?.asString ?: default

fun SlashCommandInteractionEvent.booleanOption(name: String, default: Boolean = false): Boolean =
    getOption(name)?.asBoolean ?: default

fun SlashCommandInteractionEvent.memberOption(name: String): Member? =
    getOption(name)?.asMember

fun SlashCommandInteractionEvent.userOption(name: String): User? =
    getOption(name)?.asUser
