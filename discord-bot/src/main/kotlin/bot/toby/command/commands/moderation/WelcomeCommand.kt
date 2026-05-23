package bot.toby.command.commands.moderation

import bot.toby.welcome.WelcomeMessageRenderer
import core.command.CommandContext
import database.dto.ConfigDto.Configurations
import database.dto.UserDto
import database.service.AutoRoleService
import database.service.ConfigService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Role
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
            SUB_CONFIGURE_WELCOME -> handleConfigure(event, guild.id, isWelcome = true)
            SUB_CONFIGURE_GOODBYE -> handleConfigure(event, guild.id, isWelcome = false)
            SUB_AUTOROLE_ADD -> handleAutoRoleAdd(event, guild.idLong)
            SUB_AUTOROLE_REMOVE -> handleAutoRoleRemove(event, guild.idLong)
            else -> event.reply("Unknown subcommand `$sub`.").setEphemeral(true).queue()
        }
    }

    private fun handleConfigure(
        event: SlashCommandInteractionEvent,
        guildId: String,
        isWelcome: Boolean,
    ) {
        val enabled = event.getOption(OPT_ENABLED)?.asBoolean ?: return
        // `setChannelTypes(ChannelType.TEXT)` on the OptionData guarantees
        // Discord only allows text channels in this slot, so `asTextChannel()`
        // is safe — Discord rejects voice/category picks at the client side.
        val channelOpt = event.getOption(OPT_CHANNEL)?.asChannel?.asTextChannel()
        val message = event.getOption(OPT_MESSAGE)?.asString

        val keyEnabled = if (isWelcome) Configurations.WELCOME_ENABLED else Configurations.GOODBYE_ENABLED
        val keyChannel = if (isWelcome) Configurations.WELCOME_CHANNEL else Configurations.GOODBYE_CHANNEL
        val keyMessage = if (isWelcome) Configurations.WELCOME_MESSAGE else Configurations.GOODBYE_MESSAGE

        val rows = mutableListOf<Pair<String, String>>()
        rows += keyEnabled.configValue to enabled.toString()
        if (channelOpt != null) {
            rows += keyChannel.configValue to channelOpt.id
        }
        if (message != null) {
            rows += keyMessage.configValue to message
        }
        configService.upsertAll(guildId, rows)

        val label = if (isWelcome) "Welcome" else "Goodbye"
        val summary = buildString {
            append("**$label settings saved**\n")
            append("• Enabled: ").append(enabled).append('\n')
            if (channelOpt != null) append("• Channel: ").append(channelOpt.asMention).append('\n')
            if (message != null) append("• Message: `").append(message).append('`')
        }
        event.reply(summary).setEphemeral(true).queue()
    }

    private fun handleAutoRoleAdd(event: SlashCommandInteractionEvent, guildId: Long) {
        val role = event.getOption(OPT_ROLE)?.asRole ?: return
        validateRole(event, role)?.let {
            event.reply(it).setEphemeral(true).queue()
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

    private fun validateRole(event: SlashCommandInteractionEvent, role: Role): String? {
        if (role.isPublicRole) return "Cannot auto-assign @everyone."
        if (role.isManaged) return "${role.asMention} is managed by an integration and can't be assigned by the bot."
        val self = event.guild?.selfMember
        if (self != null && !self.canInteract(role)) {
            return "${role.asMention} sits above TobyBot's role — move TobyBot's role higher to allow assignment."
        }
        return null
    }

    private fun handleShow(event: SlashCommandInteractionEvent, guildId: Long, guildName: String) {
        val welcomeEnabled = readBool(guildId.toString(), Configurations.WELCOME_ENABLED)
        val welcomeChannel = readConfig(guildId.toString(), Configurations.WELCOME_CHANNEL)
        val welcomeMessage = readConfig(guildId.toString(), Configurations.WELCOME_MESSAGE)
        val goodbyeEnabled = readBool(guildId.toString(), Configurations.GOODBYE_ENABLED)
        val goodbyeChannel = readConfig(guildId.toString(), Configurations.GOODBYE_CHANNEL)
        val goodbyeMessage = readConfig(guildId.toString(), Configurations.GOODBYE_MESSAGE)
        val autoRoles = autoRoleService.listForGuild(guildId)

        val embed = EmbedBuilder()
            .setTitle("Welcome & Auto-role for $guildName")
            .addField(
                "Welcome announcement",
                buildString {
                    append("Enabled: ").append(welcomeEnabled).append('\n')
                    append("Channel: ").append(formatChannel(welcomeChannel)).append('\n')
                    append("Message: ").append(formatMessage(welcomeMessage, WelcomeMessageRenderer.DEFAULT_WELCOME))
                },
                false,
            )
            .addField(
                "Goodbye announcement",
                buildString {
                    append("Enabled: ").append(goodbyeEnabled).append('\n')
                    append("Channel: ").append(formatChannel(goodbyeChannel)).append('\n')
                    append("Message: ").append(formatMessage(goodbyeMessage, WelcomeMessageRenderer.DEFAULT_GOODBYE))
                },
                false,
            )
            .addField(
                "Auto-assigned roles (${autoRoles.size})",
                if (autoRoles.isEmpty()) "_None_"
                else autoRoles.joinToString("\n") { "• <@&${it.roleId}>" },
                false,
            )
            .build()
        event.replyEmbeds(embed).setEphemeral(true).queue()
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
