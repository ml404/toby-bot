package bot.toby.install

import common.logging.DiscordLogger
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service

/**
 * Posts the install-wizard welcome message when the bot is added to a
 * new guild. Idempotent: if `INSTALL_MODE` is already set (regardless of
 * value) the handler returns silently, so re-invites don't double-prompt.
 *
 * Channel selection prefers `guild.systemChannel`, falling back to the
 * first text channel the bot can both view and send in. If no writable
 * channel exists, the handler logs a warning and returns.
 */
@Service
class InstallWelcomeHandler(
    private val configService: ConfigService,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onGuildJoin(event: GuildJoinEvent) {
        val guild = event.guild
        val existingValue = configService.getConfigByName(Configurations.INSTALL_MODE.configValue, guild.id)?.value
        if (!existingValue.isNullOrBlank()) {
            logger.info { "Skipping welcome — guild ${guild.id} already installed ($existingValue)" }
            return
        }
        val channel = pickWelcomeChannel(guild)
        if (channel != null) {
            channel.sendMessageEmbeds(InstallWizard.welcomeEmbed(guild.name))
                .addComponents(InstallWizard.wizardButtons())
                .queue()
            logger.info { "Posted install welcome to ${guild.id} in #${channel.name}" }
            return
        }
        // No writable channel — fall back to DMing the guild owner so the
        // wizard is still discoverable in locked-down servers. The owner
        // can run /install from the server itself if they want the public
        // version.
        val owner = guild.owner ?: run {
            logger.warn { "Guild ${guild.id} (${guild.name}) has no resolvable owner — cannot post welcome" }
            return
        }
        dmOwner(guild, owner)
    }

    private fun dmOwner(guild: Guild, owner: Member) {
        owner.user.openPrivateChannel().queue({ channel ->
            channel.sendMessageEmbeds(InstallWizard.dmWelcomeEmbed(guild.name))
                .queue(
                    { logger.info { "DM'd install welcome to owner of ${guild.id} (${guild.name})" } },
                    { err -> logger.warn { "Could not DM owner of ${guild.id}: ${err.message}" } },
                )
        }, { err ->
            logger.warn { "Could not open DM with owner of ${guild.id}: ${err.message}" }
        })
    }

    private fun pickWelcomeChannel(guild: Guild): TextChannel? {
        val self = guild.selfMember
        guild.systemChannel
            ?.takeIf { self.hasPermission(it, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND) }
            ?.let { return it }
        return guild.textChannels.firstOrNull {
            self.hasPermission(it, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        }
    }
}
