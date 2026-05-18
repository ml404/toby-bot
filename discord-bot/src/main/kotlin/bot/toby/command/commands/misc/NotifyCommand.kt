package bot.toby.command.commands.misc

import common.notification.NotificationChannelKind
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.UserDto
import database.service.UserNotificationPrefService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class NotifyCommand @Autowired constructor(
    private val prefService: UserNotificationPrefService
) : MiscCommand {

    override val name: String = "notify"
    override val description: String =
        "Manage which Toby Bot DMs you. Defaults are sensible — only opt in to the noisier ones if you want."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("list", "Show your current notification preferences."),
        SubcommandData("set", "Turn a notification kind on or off.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_KIND, "Which notification to change.", true)
                    .addChoices(NotificationChannelKind.entries.map { Choice(it.displayName, it.name) }),
                OptionData(OptionType.BOOLEAN, OPT_ON, "true = receive DMs, false = stop.", true)
            )
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val guild = event.guild ?: run {
            event.hook.replyEphemeralAndDelete("This command can only be used in a server.", deleteDelay)
            return
        }

        when (event.subcommandName) {
            "list" -> handleList(event, guild.idLong, deleteDelay)
            "set" -> handleSet(event, guild.idLong, deleteDelay)
            else -> event.hook.replyEphemeralAndDelete("Unknown subcommand.", deleteDelay)
        }
    }

    private fun handleList(
        event: net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent,
        guildId: Long,
        deleteDelay: Int
    ) {
        val discordId = event.user.idLong
        val rows = prefService.listForUser(discordId, guildId).associateBy { it.channelKind }
        val body = buildString {
            NotificationChannelKind.entries.forEach { kind ->
                val explicit = rows[kind.name]?.optIn
                val effective = explicit ?: kind.defaultOptIn
                val marker = if (effective) "✅" else "🚫"
                val tag = if (explicit == null) " *(default)*" else ""
                append(marker).append(" **").append(kind.displayName).append("**")
                append(tag).append('\n')
                append("> ").append(kind.description).append('\n')
            }
            append("\nUse `/notify set <kind> <on/off>` to change a preference.")
        }
        val embed = EmbedBuilder()
            .setTitle("Notification preferences")
            .setDescription(body)
            .setColor(Color(0x60A5FA))
            .build()
        event.hook.replyEphemeralEmbedAndDelete(embed, deleteDelay)
    }

    private fun handleSet(
        event: net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent,
        guildId: Long,
        deleteDelay: Int
    ) {
        val kindCode = event.getOption(OPT_KIND)?.asString
        val on = event.getOption(OPT_ON)?.asBoolean
        if (kindCode == null || on == null) {
            event.hook.replyEphemeralAndDelete("Missing kind or on/off value.", deleteDelay)
            return
        }
        val kind = NotificationChannelKind.fromCode(kindCode) ?: run {
            event.hook.replyEphemeralAndDelete("Unknown notification kind: $kindCode", deleteDelay)
            return
        }
        prefService.setPref(event.user.idLong, guildId, kind, on)
        val verb = if (on) "enabled" else "disabled"
        event.hook.replyEphemeralAndDelete(
            "**${kind.displayName}** $verb. You can change this again with `/notify set`.",
            deleteDelay
        )
    }

    companion object {
        private const val OPT_KIND = "kind"
        private const val OPT_ON = "on"
    }
}
