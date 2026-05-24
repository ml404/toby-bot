package bot.toby.command.commands.moderation

import bot.toby.welcome.AnnouncementKind
import common.discord.AutoRoleValidator
import core.command.CommandContext
import database.dto.guild.ConfigDto.Configurations
import database.dto.user.UserDto
import database.service.guild.AutoRoleService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/welcome <subcommand>` — owner-only configuration for the per-member
 * welcome / goodbye announcements (six [Configurations] keys) plus the
 * multi-row `auto_role` table.
 *
 * Slash-command options (not modals) are used here for two reasons:
 *   - the welcome / goodbye message is a free-form string that doesn't
 *     map cleanly onto the typed [SetConfigFieldValidator] specs used
 *     by every existing `/setconfig` modal;
 *   - the auto-role flow is multi-row by nature (one slash invocation
 *     per role) and modals can't bind multiple roles in one shot.
 *
 * Settings are also editable via the web `/moderation/{guildId}/welcome`
 * tab; both paths write to the same [ConfigService] / [AutoRoleService]
 * so they stay in sync.
 */
@Component
class WelcomeCommand @Autowired constructor(
    private val configService: ConfigService,
    private val autoRoleService: AutoRoleService,
) : ModerationCommand {

    override val name: String = NAME
    override val description: String =
        "Configure the per-member welcome / goodbye announcements and roles assigned on join."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_CONFIGURE_WELCOME, "Configure the welcome announcement (owner-only).")
            .addOptions(
                OptionData(OptionType.BOOLEAN, OPT_ENABLED, "Toggle welcome announcements on or off.", true),
                OptionData(OptionType.CHANNEL, OPT_CHANNEL, "Channel to post in (defaults to system channel).", false)
                    .setChannelTypes(ChannelType.TEXT),
                OptionData(OptionType.STRING, OPT_MESSAGE, "Message template. Placeholders: {user} {user.name} {server} {membercount}.", false),
            ),
        SubcommandData(SUB_CONFIGURE_GOODBYE, "Configure the goodbye announcement (owner-only).")
            .addOptions(
                OptionData(OptionType.BOOLEAN, OPT_ENABLED, "Toggle goodbye announcements on or off.", true),
                OptionData(OptionType.CHANNEL, OPT_CHANNEL, "Channel to post in (defaults to system channel).", false)
                    .setChannelTypes(ChannelType.TEXT),
                OptionData(OptionType.STRING, OPT_MESSAGE, "Message template. Placeholders: {user} {user.name} {server} {membercount}.", false),
            ),
        SubcommandData(SUB_AUTOROLE_ADD, "Auto-assign a role to every new member (owner-only).")
            .addOptions(
                OptionData(OptionType.ROLE, OPT_ROLE, "Role to add to the auto-assign list.", true),
            ),
        SubcommandData(SUB_AUTOROLE_REMOVE, "Stop auto-assigning a role on join (owner-only).")
            .addOptions(
                OptionData(OptionType.ROLE, OPT_ROLE, "Role to remove from the auto-assign list.", true),
            ),
        SubcommandData(SUB_SHOW, "Show current welcome / goodbye / auto-role configuration."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val guild = event.guild ?: run {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }
        val sub = event.subcommandName ?: run {
            event.reply("Pick a subcommand — see `/welcome` autocomplete.").setEphemeral(true).queue()
            return
        }
        if (sub == SUB_SHOW) {
            handleShow(event, guild.idLong, guild.name)
            return
        }
        if (ctx.member?.isOwner != true) {
            event.reply("Only the server owner can change welcome / auto-role settings.")
                .setEphemeral(true).queue()
            return
        }
        when (sub) {
            SUB_CONFIGURE_WELCOME -> handleConfigure(event, guild.id, AnnouncementKind.WELCOME)
            SUB_CONFIGURE_GOODBYE -> handleConfigure(event, guild.id, AnnouncementKind.GOODBYE)
            SUB_AUTOROLE_ADD -> handleAutoRoleAdd(event, guild.idLong)
            SUB_AUTOROLE_REMOVE -> handleAutoRoleRemove(event, guild.idLong)
            else -> event.reply("Unknown subcommand `$sub`.").setEphemeral(true).queue()
        }
    }

    private fun handleConfigure(
        event: SlashCommandInteractionEvent,
        guildId: String,
        kind: AnnouncementKind,
    ) {
        val enabled = event.getOption(OPT_ENABLED)?.asBoolean ?: return
        // `setChannelTypes(ChannelType.TEXT)` on the OptionData guarantees
        // Discord only allows text channels in this slot, so `asTextChannel()`
        // is safe — Discord rejects voice/category picks at the client side.
        val channelOpt = event.getOption(OPT_CHANNEL)?.asChannel?.asTextChannel()
        val message = event.getOption(OPT_MESSAGE)?.asString

        val rows = mutableListOf<Pair<String, String>>()
        rows += kind.enabledKey.configValue to enabled.toString()
        if (channelOpt != null) {
            rows += kind.channelKey.configValue to channelOpt.id
        }
        if (message != null) {
            rows += kind.messageKey.configValue to message
        }
        configService.upsertAll(guildId, rows)

        val summary = buildString {
            append("**${kind.label} settings saved**\n")
            append("• Enabled: ").append(enabled).append('\n')
            if (channelOpt != null) append("• Channel: ").append(channelOpt.asMention).append('\n')
            if (message != null) append("• Message: `").append(message).append('`')
        }
        event.reply(summary).setEphemeral(true).queue()
    }

    private fun handleAutoRoleAdd(event: SlashCommandInteractionEvent, guildId: Long) {
        val role = event.getOption(OPT_ROLE)?.asRole ?: return
        val self = event.guild?.selfMember
        val error = if (self == null) "Could not resolve the bot's member entry in this server."
        else AutoRoleValidator.validate(role, self)
        if (error != null) {
            event.reply(error).setEphemeral(true).queue()
            return
        }
        autoRoleService.add(guildId, role.idLong)
        event.reply("Auto-role added: ${role.asMention}. New members will receive this role.")
            .setEphemeral(true).queue()
    }

    private fun handleAutoRoleRemove(event: SlashCommandInteractionEvent, guildId: Long) {
        val role = event.getOption(OPT_ROLE)?.asRole ?: return
        autoRoleService.delete(guildId, role.idLong)
        event.reply("Auto-role removed: ${role.asMention}. New members will no longer receive it.")
            .setEphemeral(true).queue()
    }

    private fun handleShow(event: SlashCommandInteractionEvent, guildId: Long, guildName: String) {
        val autoRoles = autoRoleService.listForGuild(guildId)

        val embed = EmbedBuilder()
            .setTitle("Welcome & Auto-role for $guildName")
            .also { builder ->
                for (kind in AnnouncementKind.entries) {
                    builder.addField(
                        "${kind.label} announcement",
                        renderShowSection(guildId.toString(), kind),
                        false,
                    )
                }
            }
            .addField(
                "Auto-assigned roles (${autoRoles.size})",
                if (autoRoles.isEmpty()) "_None_"
                else autoRoles.joinToString("\n") { "• <@&${it.roleId}>" },
                false,
            )
            .build()
        event.replyEmbeds(embed).setEphemeral(true).queue()
    }

    private fun renderShowSection(guildId: String, kind: AnnouncementKind): String = buildString {
        append("Enabled: ").append(readBool(guildId, kind.enabledKey)).append('\n')
        append("Channel: ").append(formatChannel(readConfig(guildId, kind.channelKey))).append('\n')
        append("Message: ").append(formatMessage(readConfig(guildId, kind.messageKey), kind.defaultTemplate))
    }

    private fun readConfig(guildId: String, key: Configurations): String? =
        configService.getConfigByName(key.configValue, guildId)?.value

    private fun readBool(guildId: String, key: Configurations): Boolean =
        readConfig(guildId, key).equals("true", ignoreCase = true)

    private fun formatChannel(raw: String?): String {
        val id = raw?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: return "_system channel (fallback)_"
        return "<#$id>"
    }

    private fun formatMessage(raw: String?, fallback: String): String {
        val text = raw?.takeIf { it.isNotBlank() } ?: return "_default — `$fallback`_"
        return "`$text`"
    }

    companion object {
        const val NAME = "welcome"
        const val SUB_CONFIGURE_WELCOME = "configure-welcome"
        const val SUB_CONFIGURE_GOODBYE = "configure-goodbye"
        const val SUB_AUTOROLE_ADD = "autorole-add"
        const val SUB_AUTOROLE_REMOVE = "autorole-remove"
        const val SUB_SHOW = "show"
        const val OPT_ENABLED = "enabled"
        const val OPT_CHANNEL = "channel"
        const val OPT_MESSAGE = "message"
        const val OPT_ROLE = "role"
    }
}
