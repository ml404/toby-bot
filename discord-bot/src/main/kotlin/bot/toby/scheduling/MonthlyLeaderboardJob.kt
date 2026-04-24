package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.MonthlyCreditSnapshotDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.UserService
import database.service.VoiceSessionService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.awt.Color
import java.time.LocalDate
import java.time.ZoneOffset

@Component
@Profile("prod")
class MonthlyLeaderboardJob @Autowired constructor(
    private val jda: JDA,
    private val userService: UserService,
    private val voiceSessionService: VoiceSessionService,
    private val snapshotService: MonthlyCreditSnapshotService,
    private val configService: ConfigService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    companion object {
        const val TOP_N = 10
    }

    @Scheduled(cron = "0 0 12 1 * *", zone = "UTC")
    fun postMonthlyLeaderboard() {
        val today = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val previousMonthStart = today.minusMonths(1)
        logger.info { "Running monthly leaderboard job for month starting $previousMonthStart" }

        jda.guildCache.forEach { guild ->
            runCatching { postForGuild(guild, today, previousMonthStart) }
                .onFailure { logger.error("Monthly leaderboard failed for guild ${guild.idLong}: ${it.message}") }
        }
    }

    private fun postForGuild(guild: Guild, thisMonthStart: LocalDate, previousMonthStart: LocalDate) {
        val users = userService.listGuildUsers(guild.idLong).filterNotNull()
        if (users.isEmpty()) {
            logger.info { "Skipping guild ${guild.idLong} — no tracked users." }
            return
        }

        val priorSnapshots = snapshotService.listForGuildDate(guild.idLong, previousMonthStart)
            .associateBy { it.discordId }

        val previousMonthRange = previousMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC) to
                thisMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC)
        val voiceSecondsByUser = voiceSessionService.sumCountedSecondsInRangeByUser(
            guild.idLong,
            previousMonthRange.first,
            previousMonthRange.second
        )

        val rows = users.map { dto ->
            val current = dto.socialCredit ?: 0L
            val baseline = priorSnapshots[dto.discordId]?.socialCredit
            val creditsDelta = if (baseline == null) current else current - baseline
            MonthlyRow(
                discordId = dto.discordId,
                creditsDelta = creditsDelta,
                voiceSeconds = voiceSecondsByUser[dto.discordId] ?: 0L
            )
        }.sortedWith(
            compareByDescending<MonthlyRow> { it.creditsDelta }
                .thenByDescending { it.voiceSeconds }
        ).take(TOP_N)

        val channel = resolveChannel(guild)
        if (channel == null) {
            logger.warn(
                "No channel to post monthly leaderboard in guild ${guild.idLong}. " +
                        "Set one via /setleaderboardchannel or grant the bot a writable system channel."
            )
        } else {
            val embed = buildEmbed(guild, previousMonthStart, rows)
            runCatching { channel.sendMessageEmbeds(embed).queue() }
                .onFailure { logger.error("Could not send leaderboard to ${channel.id}: ${it.message}") }
        }

        writeCurrentMonthSnapshot(guild.idLong, users, thisMonthStart)
    }

    private fun resolveChannel(guild: Guild): TextChannel? {
        val bot = guild.selfMember
        val configured = configService.getConfigByName(
            ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue,
            guild.id
        )?.value?.toLongOrNull()
        configured?.let {
            val channel = guild.getTextChannelById(it)
            if (channel != null && bot.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
                return channel
            }
        }
        return guild.systemChannel?.takeIf {
            bot.hasPermission(it, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
        }
    }

    private fun buildEmbed(guild: Guild, previousMonthStart: LocalDate, rows: List<MonthlyRow>): net.dv8tion.jda.api.entities.MessageEmbed {
        val monthLabel = previousMonthStart.month.name.lowercase().replaceFirstChar { it.titlecase() } +
                " ${previousMonthStart.year}"

        val description = if (rows.isEmpty()) {
            "No activity recorded for $monthLabel."
        } else {
            rows.mapIndexed { idx, row ->
                val name = guild.getMemberById(row.discordId)?.effectiveName ?: "Unknown (${row.discordId})"
                val voice = formatDuration(row.voiceSeconds)
                "**#${idx + 1}** $name — ${row.creditsDelta} credits · $voice"
            }.joinToString("\n")
        }

        return EmbedBuilder()
            .setTitle("Monthly Social Credit Leaderboard — $monthLabel")
            .setDescription(description)
            .setColor(Color(0xFFD700))
            .setFooter("Totals reflect credits earned (including voice-time) and counted voice time.")
            .build()
    }

    private fun writeCurrentMonthSnapshot(guildId: Long, users: List<database.dto.UserDto>, snapshotDate: LocalDate) {
        users.forEach { dto ->
            snapshotService.upsert(
                MonthlyCreditSnapshotDto(
                    discordId = dto.discordId,
                    guildId = guildId,
                    snapshotDate = snapshotDate,
                    socialCredit = dto.socialCredit ?: 0L,
                    tobyCoins = dto.tobyCoins
                )
            )
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private data class MonthlyRow(
        val discordId: Long,
        val creditsDelta: Long,
        val voiceSeconds: Long
    )
}
