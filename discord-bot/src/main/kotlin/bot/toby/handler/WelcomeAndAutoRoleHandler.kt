package bot.toby.handler

import bot.toby.welcome.WelcomeMessageRenderer
import common.logging.DiscordLogger
import database.dto.ConfigDto.Configurations
import database.service.AutoRoleService
import database.service.ConfigService
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
 * other. Channel resolution prefers the configured `*_CHANNEL`; if that
 * row is blank, unparseable, missing, or non-postable, falls back to the
 * guild's system channel. If neither is postable the listener logs and
 * returns silently — never let a transient JDA-side failure kill the
 * dispatch thread.
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
            .onFailure {
                logger.error {
                    "Auto-role assignment failed for guild ${guild.id} member ${member.id}: " +
                        "${it.javaClass.simpleName}: ${it.message}"
                }
            }
        runCatching { postWelcomeIfEnabled(guild, member) }
            .onFailure {
                logger.error {
                    "Welcome announcement failed for guild ${guild.id} member ${member.id}: " +
                        "${it.javaClass.simpleName}: ${it.message}"
                }
            }
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        val guild = event.guild
        val user = event.user
        runCatching { postGoodbyeIfEnabled(guild, user, event.member) }
            .onFailure {
                logger.error {
                    "Goodbye announcement failed for guild ${guild.id} user ${user.id}: " +
                        "${it.javaClass.simpleName}: ${it.message}"
                }
            }
    }

    private fun assignAutoRoles(guild: Guild, member: Member) {
        val rows = autoRoleService.listForGuild(guild.idLong)
        if (rows.isEmpty()) return
        val self = guild.selfMember
        if (!self.hasPermission(Permission.MANAGE_ROLES)) {
            logger.warn {
                "Skipping auto-role for guild ${guild.id} — bot lacks MANAGE_ROLES"
            }
            return
        }
        for (row in rows) {
            val role = guild.getRoleById(row.roleId)
            if (role == null) {
                logger.warn { "Auto-role ${row.roleId} no longer exists in guild ${guild.id}" }
                continue
            }
            if (role.isManaged) {
                logger.warn { "Skipping managed role ${role.id} (${role.name}) — integration-owned" }
                continue
            }
            if (!self.canInteract(role)) {
                logger.warn {
                    "Bot can't assign role ${role.id} (${role.name}) — its role sits above the bot's"
                }
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

    private fun postWelcomeIfEnabled(guild: Guild, member: Member) {
        if (!isFlagEnabled(guild.id, Configurations.WELCOME_ENABLED)) return
        val channel = resolveChannel(guild, Configurations.WELCOME_CHANNEL) ?: run {
            logger.warn { "Welcome enabled for ${guild.id} but no postable channel resolved" }
            return
        }
        val template = readConfig(guild.id, Configurations.WELCOME_MESSAGE)
        val text = WelcomeMessageRenderer.render(template, guild, member.user, member.effectiveName)
        channel.sendMessageEmbeds(buildEmbed(member.user, text, isWelcome = true)).queue(
            { logger.info { "Posted welcome to ${guild.id} #${channel.name}" } },
            { err ->
                logger.warn { "Failed to post welcome to ${guild.id} #${channel.name}: ${err.message}" }
            },
        )
    }

    private fun postGoodbyeIfEnabled(guild: Guild, user: User, member: Member?) {
        if (!isFlagEnabled(guild.id, Configurations.GOODBYE_ENABLED)) return
        val channel = resolveChannel(guild, Configurations.GOODBYE_CHANNEL) ?: run {
            logger.warn { "Goodbye enabled for ${guild.id} but no postable channel resolved" }
            return
        }
        val template = readConfig(guild.id, Configurations.GOODBYE_MESSAGE)
        val text = WelcomeMessageRenderer.renderGoodbye(
            template,
            guild,
            user,
            member?.effectiveName,
        )
        channel.sendMessageEmbeds(buildEmbed(user, text, isWelcome = false)).queue(
            { logger.info { "Posted goodbye to ${guild.id} #${channel.name}" } },
            { err ->
                logger.warn { "Failed to post goodbye to ${guild.id} #${channel.name}: ${err.message}" }
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
        val raw = readConfig(guild.id, key)
        val configured = raw?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?.let { guild.getTextChannelById(it) }
            ?.takeIf { canPost(guild, it) }
        if (configured != null) return configured
        return guild.systemChannel?.takeIf { canPost(guild, it) }
    }

    private fun canPost(guild: Guild, channel: TextChannel): Boolean {
        val self = guild.selfMember
        return self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
    }

    private fun buildEmbed(user: User, text: String, isWelcome: Boolean): MessageEmbed {
        val builder = EmbedBuilder()
            .setDescription(text)
            .setColor(if (isWelcome) WELCOME_COLOR else GOODBYE_COLOR)
            .setThumbnail(user.effectiveAvatarUrl)
        return builder.build()
    }

    companion object {
        private const val WELCOME_COLOR = 0x57F287 // Discord "green"
        private const val GOODBYE_COLOR = 0xED4245 // Discord "red"
    }
}
