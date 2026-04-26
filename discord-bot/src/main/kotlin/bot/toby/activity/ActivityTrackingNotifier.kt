package bot.toby.activity

import common.events.ActivityTrackingEnabled
import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations.ACTIVITY_TRACKING_NOTIFIED
import database.service.ConfigService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class ActivityTrackingNotifier @Autowired constructor(
    private val configService: ConfigService,
    // @Lazy breaks a context-startup cycle introduced in #288:
    //   JDA -> StartUpHandler -> CommandManager -> SetConfigCommand
    //       -> ActivityTrackingNotifier -> JDA
    // The JDA reference is only dereferenced from the @EventListener (i.e.
    // post-startup), so deferring resolution is safe.
    @Lazy private val jda: JDA
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /**
     * Cross-module entry point for the web moderation UI. Web publishes an
     * [ActivityTrackingEnabled] event whenever ACTIVITY_TRACKING is set to
     * `true`; this listener resolves the guild via JDA and runs the same
     * first-enable flow the Discord `/setconfig` command runs. Already-
     * notified guilds short-circuit inside [notifyMembersOnFirstEnable].
     */
    @EventListener
    fun onActivityTrackingEnabled(event: ActivityTrackingEnabled) {
        val guild = jda.getGuildById(event.guildId)
        if (guild == null) {
            logger.warn(
                "ActivityTrackingEnabled received for guild ${event.guildId} but JDA has no matching guild; skipping."
            )
            return
        }
        notifyMembersOnFirstEnable(guild)
    }

    fun notifyMembersOnFirstEnable(guild: Guild) {
        val guildIdStr = guild.id
        val existing = configService.getConfigByName(ACTIVITY_TRACKING_NOTIFIED.configValue, guildIdStr)
        if (existing?.value?.equals("true", ignoreCase = true) == true) {
            logger.info { "Guild ${guild.idLong} has already been notified about activity tracking; skipping DMs." }
            return
        }

        val message = buildDmBody(guild.name)
        guild.members
            .map { it.user }
            .filter { !it.isBot }
            .forEach { user -> sendDmSafely(user, message) }

        val flag = ConfigDto(ACTIVITY_TRACKING_NOTIFIED.configValue, "true", guildIdStr)
        if (existing != null && existing.guildId == guildIdStr) {
            configService.updateConfig(flag)
        } else {
            configService.createNewConfig(flag)
        }
    }

    private fun sendDmSafely(user: User, body: String) {
        runCatching {
            user.openPrivateChannel().queue({ pm ->
                pm.sendMessage(body).queue(null) { err ->
                    logger.warn("Could not DM ${user.id} about activity tracking: ${err.message}")
                }
            }, { err ->
                logger.warn("Could not open DM with ${user.id}: ${err.message}")
            })
        }.onFailure {
            logger.warn("DM dispatch failed for ${user.id}: ${it.message}")
        }
    }

    private fun buildDmBody(guildName: String): String = buildString {
        append("Hi — the owner of **")
        append(guildName)
        append("** has enabled game-activity tracking via TobyBot.\n\n")
        append("**What is recorded:** only the name of any game you are *playing* (Discord activity type `PLAYING`). ")
        append("Music listening (Spotify), streaming, and custom statuses are ignored. ")
        append("Session start and end times are retained for 30 days; only per-month totals are kept longer, ")
        append("and those are purged after 12 months.\n\n")
        append("**Where it's shown:** `/activity server` surfaces the server's top games this month (with opt-outs excluded); ")
        append("`/activity me` shows your own top games.\n\n")
        append("**To opt out:** run `/activity tracking-off` in the server. You can opt back in any time with `/activity tracking-on`.")
    }
}
