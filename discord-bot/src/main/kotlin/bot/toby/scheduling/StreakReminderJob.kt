package bot.toby.scheduling

import bot.toby.notify.NotificationRouter
import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import database.service.social.LoginStreakService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.awt.Color
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 23:00 UTC nudge for users with an active streak who haven't claimed
 * today. Routed through [NotificationRouter], which checks
 * [NotificationChannelKind.STREAK_REMINDER] opt-in — that kind defaults
 * to off, so existing servers stay quiet; users explicitly opt in via
 * `/notify set STREAK_REMINDER on` or the web preferences page.
 *
 * Per-guild error isolation via `runCatching`. Prod-profile only;
 * tests exercise [LoginStreakService.findActiveStreaksDueForReminder]
 * directly.
 */
@Component
@Profile("prod")
class StreakReminderJob @Autowired constructor(
    private val jda: JDA,
    private val loginStreakService: LoginStreakService,
    private val notificationRouter: NotificationRouter,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${app.base-url:}") private val webBaseUrl: String = "",
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @Scheduled(cron = "0 0 23 * * *", zone = "UTC")
    fun runHourly() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        logger.info { "Running streak-reminder job for $today" }

        jda.guildCache.forEach { guild ->
            runCatching { remindForGuild(guild.idLong, today) }
                .onFailure {
                    logger.error("Streak reminder failed for guild ${guild.idLong}: ${it.message}")
                }
        }
    }

    private fun remindForGuild(guildId: Long, today: LocalDate) {
        val due = loginStreakService.findActiveStreaksDueForReminder(guildId, today)
        if (due.isEmpty()) {
            logger.info { "No at-risk streaks for guild $guildId; skipping." }
            return
        }

        var dispatched = 0
        due.forEach { row ->
            notificationRouter.dispatch(
                kind = NotificationChannelKind.STREAK_REMINDER,
                discordId = row.discordId,
                guildId = guildId,
            ) {
                dm {
                    MessageCreateBuilder().setEmbeds(
                        EmbedBuilder()
                            .setTitle("🔥 Don't lose your ${row.currentStreak}-day streak")
                            .setDescription(
                                "Your daily streak resets at midnight UTC. " +
                                    "Run `/daily` (or claim from the dashboard) to keep it alive."
                            )
                            .setColor(Color(0xF59E0B))
                            .build()
                    ).build()
                }
                push {
                    PushPayload(
                        title = "🔥 Don't lose your ${row.currentStreak}-day streak",
                        body = "Claim today's /daily before midnight UTC to keep it alive.",
                        deepLink = webBaseUrl.takeIf { it.isNotBlank() }
                            ?.let { "$it/profile/$guildId" },
                    )
                }
            }
            dispatched++
        }
        logger.info { "Streak reminder for guild $guildId: at-risk=${due.size} (opt-in checked per user)" }
    }
}
