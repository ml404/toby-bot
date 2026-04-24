package bot.toby.handler

import bot.toby.activity.ActivityTrackingService
import common.logging.DiscordLogger
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.user.update.UserUpdateActivitiesEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ActivityEventHandler @Autowired constructor(
    private val activityTrackingService: ActivityTrackingService
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onUserUpdateActivities(event: UserUpdateActivitiesEvent) {
        val member = event.member
        if (member.user.isBot) return
        val guildId = event.guild.idLong
        val discordId = member.idLong

        val previousGame = primaryGame(event.oldValue)
        val currentGame = primaryGame(event.newValue)

        if (previousGame == currentGame) return

        val now = Instant.now()
        if (previousGame != null) {
            activityTrackingService.closeOpenSessionForUser(discordId, guildId, now)
        }
        if (currentGame != null) {
            activityTrackingService.openSession(discordId, guildId, currentGame, now)
        }
        logger.info { "Activity change for $discordId in $guildId: '$previousGame' -> '$currentGame'" }
    }

    private fun primaryGame(activities: List<Activity>?): String? {
        if (activities.isNullOrEmpty()) return null
        val playing = activities.firstOrNull { it.type == Activity.ActivityType.PLAYING } ?: return null
        return playing.name.takeIf { it.isNotBlank() }
    }
}
