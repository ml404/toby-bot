package bot.toby.command.commands.misc

import common.notification.NotificationChannelKind
import common.notification.Surface
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.user.UserNotificationPrefService
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
        "Manage notifications. Each kind has per-surface preferences (DM, channel ping, push)."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("list", "Show your current notification preferences across DM, channel, and push."),
        SubcommandData("set", "Turn a notification kind on or off for a specific surface.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_KIND, "Which notification to change.", true)
                    .addChoices(NotificationChannelKind.entries.map { Choice(it.displayName, it.name) }),
                OptionData(OptionType.STRING, OPT_SURFACE, "DM, CHANNEL, or PUSH.", true)
                    .addChoices(Surface.entries.map { Choice(it.name, it.name) }),
                OptionData(OptionType.BOOLEAN, OPT_ON, "true = receive on this surface, false = don't.", true)
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
        // Index explicit rows by (kind, surface) for O(1) lookup below.
        val rows = prefService.listForUser(discordId, guildId)
            .associateBy { it.channelKind to it.surface }
        val body = buildString {
            NotificationChannelKind.entries.forEach { kind ->
                append("**").append(kind.displayName).append("**\n")
                append("> ").append(kind.description).append('\n')
                kind.supportedSurfaces.forEach { surface ->
                    val explicit = rows[kind.name to surface.name]?.optIn
                    val effective = explicit ?: kind.defaultOptIn(surface)
                    val marker = if (effective) "✅" else "🚫"
                    val defaultTag = if (explicit == null) " *(default)*" else ""
                    append(marker).append(' ').append(surface.name).append(defaultTag).append('\n')
                }
                append('\n')
            }
            append("Use `/notify set <kind> <surface> <on/off>` to change a preference.")
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
        val surfaceCode = event.getOption(OPT_SURFACE)?.asString
        val on = event.getOption(OPT_ON)?.asBoolean
        if (kindCode == null || surfaceCode == null || on == null) {
            event.hook.replyEphemeralAndDelete("Missing kind, surface, or on/off value.", deleteDelay)
            return
        }
        val kind = NotificationChannelKind.fromCode(kindCode) ?: run {
            event.hook.replyEphemeralAndDelete("Unknown notification kind: $kindCode", deleteDelay)
            return
        }
        val surface = runCatching { Surface.valueOf(surfaceCode.uppercase()) }.getOrNull() ?: run {
            event.hook.replyEphemeralAndDelete("Unknown surface: $surfaceCode", deleteDelay)
            return
        }
        if (!kind.supports(surface)) {
            event.hook.replyEphemeralAndDelete(
                "**${kind.displayName}** doesn't support the **${surface.name}** surface. " +
                    "Supported: ${kind.supportedSurfaces.joinToString { it.name }}.",
                deleteDelay
            )
            return
        }
        prefService.setPref(event.user.idLong, guildId, kind, surface, on)
        val verb = if (on) "enabled" else "disabled"
        event.hook.replyEphemeralAndDelete(
            "**${kind.displayName}** on **${surface.name}** $verb. " +
                "Change again with `/notify set`.",
            deleteDelay
        )
    }

    companion object {
        private const val OPT_KIND = "kind"
        private const val OPT_SURFACE = "surface"
        private const val OPT_ON = "on"
    }
}
