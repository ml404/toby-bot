package bot.toby.install

import common.logging.DiscordLogger
import database.dto.guild.ConfigDto.Configurations
import database.service.activity.InstallEventService
import database.service.guild.ConfigService
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
    private val installEventService: InstallEventService,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onGuildJoin(event: GuildJoinEvent) {
        val guild = event.guild
        // Record the JOIN unconditionally (even on re-invite) so the
        // operator churn dashboard sees every lifecycle transition. Kept
        // separate from the welcome try/catch so a ledger write failure
        // never suppresses the welcome, and vice-versa.
        runCatching { installEventService.recordJoin(guild.idLong) }
            .onFailure { logger.error { "Failed to record JOIN for guild ${guild.id}: ${it.message}" } }
        try {
            postWelcomeOrDmOwner(guild)
        } catch (e: Exception) {
            // Bot just joined the guild — never let a transient failure
            // (DB outage on the sentinel read, JDA queue failure, etc.)
            // propagate up and kill the listener thread. The owner can
            // always run /install manually.
            logger.error {
                "Install welcome failed for guild ${guild.id} (${guild.name}): " +
                    "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun postWelcomeOrDmOwner(guild: Guild) {
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
            logger.error { "Guild ${guild.id} (${guild.name}) has no resolvable owner AND no writable channel — install wizard is unreachable" }
            return
        }
        dmOwner(guild, owner)
    }

    private fun dmOwner(guild: Guild, owner: Member) {
        owner.user.openPrivateChannel().queue({ channel ->
            channel.sendMessageEmbeds(InstallWizard.dmWelcomeEmbed(guild.name))
                .queue(
                    { logger.info { "DM'd install welcome to owner of ${guild.id} (${guild.name})" } },
                    { err ->
                        // Both the guild and the owner are unreachable — the wizard is
                        // effectively undiscoverable until the owner runs /install manually.
                        // ERROR level so this surfaces in alerting, not buried in WARN noise.
                        logger.error {
                            "Could not DM owner of guild ${guild.id} (${guild.name}); install wizard " +
                                "is unreachable until /install is run manually: ${err.message}"
                        }
                    },
                )
        }, { err ->
            logger.error {
                "Could not open DM channel with owner of guild ${guild.id} (${guild.name}); " +
                    "install wizard is unreachable until /install is run manually: ${err.message}"
            }
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
