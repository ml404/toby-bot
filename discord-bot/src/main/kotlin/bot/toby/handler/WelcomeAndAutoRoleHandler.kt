package bot.toby.handler

import bot.toby.welcome.AnnouncementKind
import bot.toby.welcome.WelcomeMessageRenderer
import common.discord.AutoRoleValidator
import common.logging.DiscordLogger
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.AutoRoleService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service

/**
 * Reacts to guild membership changes:
 *  - `onGuildMemberJoin` — posts the welcome embed (if `WELCOME_ENABLED`)
 *    and assigns every role bound for the guild in [AutoRoleService].
 *  - `onGuildMemberRemove` — posts the goodbye embed (if `GOODBYE_ENABLED`).
 *
 * Each side is independently toggled so admins can run one without the
 * other. The welcome / goodbye paths are otherwise structurally identical
 * — both collapse onto [postIfEnabled] with the per-surface knowledge
 * (config keys, default text, embed accent) carried by [AnnouncementKind].
 *
 * Channel resolution prefers the configured `*_CHANNEL`; if that row is
 * blank, unparseable, missing, or non-postable, falls back to the
 * guild's system channel. If neither passes the permission check the
 * post is still attempted on the first channel that exists (a computed-
 * permission false negative degrades to a logged send failure, never a
 * silent drop); only a guild with no candidate at all skips.
 */
@Service
class WelcomeAndAutoRoleHandler(
    private val configService: ConfigService,
    private val autoRoleService: AutoRoleService,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        val guild = event.guild
        val member = event.member
        runCatching { assignAutoRoles(guild, member) }
            .onFailure { logFailure("Auto-role assignment", guild, member.id, it) }
        runCatching { postIfEnabled(AnnouncementKind.WELCOME, guild, member.user, member.effectiveName) }
            .onFailure { logFailure("Welcome announcement", guild, member.id, it) }
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        val guild = event.guild
        val user = event.user
        runCatching { postIfEnabled(AnnouncementKind.GOODBYE, guild, user, event.member?.effectiveName) }
            .onFailure { logFailure("Goodbye announcement", guild, user.id, it) }
    }

    private fun assignAutoRoles(guild: Guild, member: Member) {
        val rows = autoRoleService.listForGuild(guild.idLong)
        if (rows.isEmpty()) return
        val self = guild.selfMember
        if (!self.hasPermission(Permission.MANAGE_ROLES)) {
            logger.warn { "Skipping auto-role for guild ${guild.id} — bot lacks MANAGE_ROLES" }
            return
        }
        for (row in rows) {
            val role = guild.getRoleById(row.roleId)
            if (role == null) {
                logger.warn { "Auto-role ${row.roleId} no longer exists in guild ${guild.id}" }
                continue
            }
            val error = AutoRoleValidator.validate(role, self)
            if (error != null) {
                logger.warn { "Skipping role ${role.id} (${role.name}) for guild ${guild.id}: $error" }
                continue
            }
            guild.addRoleToMember(member, role).queue(
                null,
                { err ->
                    logger.warn {
                        "addRoleToMember failed for ${role.id} on ${member.id}: ${err.message}"
                    }
                },
            )
        }
    }

    private fun postIfEnabled(
        kind: AnnouncementKind,
        guild: Guild,
        user: User,
        memberDisplayName: String?,
    ) {
        if (!isFlagEnabled(guild.id, kind.enabledKey)) return
        val channel = resolveChannel(guild, kind.channelKey) ?: run {
            logger.warn { "${kind.label} enabled for ${guild.id} but no postable channel resolved" }
            return
        }
        val template = readConfig(guild.id, kind.messageKey)
        val text = WelcomeMessageRenderer.render(template, kind.defaultTemplate, guild, user, memberDisplayName)
        channel.sendMessageEmbeds(buildEmbed(user, text, kind.embedColor)).queue(
            { logger.info { "Posted ${kind.label} to ${guild.id} #${channel.name}" } },
            { err ->
                logger.warn { "Failed to post ${kind.label} to ${guild.id} #${channel.name}: ${err.message}" }
            },
        )
    }

    private fun isFlagEnabled(guildId: String, key: Configurations): Boolean {
        val value = readConfig(guildId, key) ?: return false
        return value.equals("true", ignoreCase = true)
    }

    private fun readConfig(guildId: String, key: Configurations): String? =
        configService.getConfigByName(key.configValue, guildId)?.value

    private fun resolveChannel(guild: Guild, key: Configurations): TextChannel? {
        val configured = readConfig(guild.id, key)
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?.let { guild.getTextChannelById(it) }
        val candidates = listOfNotNull(configured, guild.systemChannel)
        candidates.firstOrNull { canPost(guild, it) }?.let { return it }
        // Same contract as NotificationRouter.resolveChannel: when no
        // candidate passes the permission check but one exists, attempt
        // the send there rather than dropping the announcement — a
        // computed-permission false negative degrades to a logged send
        // failure instead of a silent drop.
        return candidates.firstOrNull()?.also {
            logger.warn {
                "No ${key.configValue} candidate for guild ${guild.id} passes the permission check; " +
                    "attempting best-effort post to #${it.name} anyway."
            }
        }
    }

    private fun canPost(guild: Guild, channel: TextChannel): Boolean {
        val self = guild.selfMember
        return self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
    }

    private fun buildEmbed(user: User, text: String, color: Int): MessageEmbed =
        EmbedBuilder()
            .setDescription(text)
            .setColor(color)
            .setThumbnail(user.effectiveAvatarUrl)
            .build()

    private fun logFailure(label: String, guild: Guild, subjectId: String, error: Throwable) {
        logger.error {
            "$label failed for guild ${guild.id} subject $subjectId: " +
                "${error.javaClass.simpleName}: ${error.message}"
        }
    }
}
